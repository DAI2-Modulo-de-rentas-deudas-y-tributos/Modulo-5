package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:external-payment-events;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "rentas.outbox-delay-ms=3600000"})
class ExternalPaymentEventsTests {
    @Autowired PaymentService payments;@Autowired CatalogService catalog;@Autowired DebtRepository debts;
    @Autowired ReversalService reversals;
    @Autowired ExternalObligationRepository obligations;@Autowired PaymentAllocationRepository allocations;
    @Autowired PaymentRepository paymentRepository;@Autowired OutboxRepository outbox;@Autowired ObjectMapper json;
    @Autowired IntegrationEventLogRepository integrationLogs;
    @Autowired PlanWorkflowService planWorkflow;@Autowired PaymentPlanRepository plans;@Autowired InstallmentRepository installments;
    @Autowired AuditRepository auditEntries;
    @Autowired PaymentReversalRepository reversalRequests;
    @Autowired DeferredEconomicReconciliation economicReconciliation;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach void authenticate(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("external-events-test",null),null,List.of(new SimpleGrantedAuthority("ROLE_RENTAS"),new SimpleGrantedAuthority("ROLE_CASHIER"))));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void partialM7PaymentPublishesAllocatedAmountWithoutSettledEvent()throws Exception{
        TaxpayerReference taxpayer=taxpayer("M7-PARTIAL");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,"INF-EXACT-001","100");
        Payment payment=payments.register(request(taxpayer.id,"60",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("60"))));

        OutboxEvent registered=event("paymentRegistered","M7",payment.id,debt.id);JsonNode payload=payload(registered);
        assertEnvelope(registered,payload,"paymentRegistered","PaymentAllocation");
        assertThat(payload.path("data").path("paymentId").asText()).isEqualTo("payment-"+payment.id);
        assertThat(payload.path("data").path("debtId").asText()).isEqualTo("debt-"+debt.id);
        assertThat(payload.path("data").path("originId").asText()).isEqualTo("INF-EXACT-001");
        assertThat(payload.path("data").path("originType").asText()).isEqualTo("TRAFFIC_FINE");
        assertThat(payload.path("data").path("infractionId").asText()).isEqualTo(payload.path("data").path("originId").asText());
        assertFields(payload.path("data"),"paymentId","debtId","originType","originId","infractionId","amountApplied","paidAt","remainingBalance");
        assertThat(payload.path("data").path("amountApplied").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(payload.path("data").path("remainingBalance").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(payload.path("correlationId").asText()).isEqualTo(originalM7Correlation(debt));
        assertThat(registered.idempotencyKey).isEqualTo("outbound:paymentRegistered:PaymentAllocation:"+allocations.findByPaymentId(payment.id).get(0).id+":Debt:"+debt.id);
        assertThat(events("debtSettled","M7",debt.id)).isEmpty();
    }

    @Test void totalM7PaymentPublishesOneSettledFactForCoreToRoute()throws Exception{
        TaxpayerReference taxpayer=taxpayer("M7-TOTAL");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,"INF-EXACT-002","100");
        Payment payment=payments.register(request(taxpayer.id,"100",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("100"))));

        OutboxEvent registered=event("paymentRegistered","M7",payment.id,debt.id),settled=events("debtSettled","M7",debt.id).get(0);
        assertThat(Set.of(registered.id,settled.id)).hasSize(2);
        JsonNode payload=payload(settled);assertEnvelope(settled,payload,"debtSettled","Debt");
        assertThat(payload.path("data").path("originId").asText()).isEqualTo("INF-EXACT-002");
        assertThat(payload.path("data").path("infractionId").asText()).isEqualTo(payload.path("data").path("originId").asText());
        assertThat(payload.path("data").path("outstandingBalance").decimalValue()).isZero();
        assertThat(payload.path("correlationId").asText()).isEqualTo(originalM7Correlation(debt));
    }

    @Test void totalM4PaymentUsesConcreteExternalObligationCorrelation()throws Exception{
        TaxpayerReference taxpayer=taxpayer("M4-TOTAL");Debt debt=externalDebt(taxpayer,"M4",ExternalObligationType.PERMIT_FEE,"FEE-EXACT-001","80");
        Payment payment=payments.register(request(taxpayer.id,"80",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("80"))));

        JsonNode registered=payload(event("paymentRegistered","M4",payment.id,debt.id)).path("data");
        JsonNode settled=payload(events("debtSettled","M4",debt.id).get(0)).path("data");
        assertThat(registered.path("originId").asText()).isEqualTo("FEE-EXACT-001");assertThat(registered.has("infractionId")).isFalse();
        assertThat(settled.path("originId").asText()).isEqualTo("FEE-EXACT-001");assertThat(settled.has("infractionId")).isFalse();
        assertFields(registered,"paymentId","debtId","originType","originId","amountApplied","paidAt","remainingBalance");
        assertFields(settled,"debtId","citizenId","conceptCode","originType","originId","settledAt","remainingBalance","outstandingBalance");
    }

    @Test void localDebtDoesNotPublishTargetSpecificEvents(){
        TaxpayerReference taxpayer=taxpayer("LOCAL");Debt debt=localDebt(taxpayer,"50");Payment payment=payments.register(request(taxpayer.id,"50",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("50"))));
        assertThat(eventsForPayment("paymentRegistered","M4",payment.id)).isEmpty();assertThat(eventsForPayment("paymentRegistered","M7",payment.id)).isEmpty();
        assertThat(events("debtSettled","M4",debt.id)).isEmpty();
        assertThat(integrationLogs.findFirstByBusinessKeyOrderByIdDesc("outbound-deferred:debtSettled:Debt:"+debt.id)).isPresent();
    }

    @Test void municipalDebtSettledIsDeferredWithoutChangingExternalM4M7Contracts(){
        TaxpayerReference taxpayer=taxpayer("M8-SHARED");Debt debt=localDebt(taxpayer,"50");
        payments.register(request(taxpayer.id,"50",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("50"))));

        List<OutboxEvent> published=events("debtSettled","CORE",debt.id);
        assertThat(published).isEmpty();
        assertThat(debts.findById(debt.id).orElseThrow().status).isEqualTo(DebtStatus.PAID);
        IntegrationEventLog deferred=integrationLogs.findFirstByBusinessKeyOrderByIdDesc("outbound-deferred:debtSettled:Debt:"+debt.id).orElseThrow();
        assertThat(deferred.status).isEqualTo(IntegrationEventStatus.PENDING);
    }

    @Test void mixedPaymentRoutesEachAllocationWithItsOwnAmount()throws Exception{
        TaxpayerReference taxpayer=taxpayer("MIXED");Debt m4=externalDebt(taxpayer,"habilitaciones",ExternalObligationType.COMMERCIAL_FINE,"FINE-MIXED","100");Debt m7=externalDebt(taxpayer,"transito",ExternalObligationType.TRAFFIC_INFRACTION,"INF-MIXED","100");
        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,PaymentMethod.TRANSFER,new BigDecimal("100"),List.of(new ApiDtos.AllocationRequest(m4.id,new BigDecimal("40")),new ApiDtos.AllocationRequest(m7.id,new BigDecimal("60")))));

        JsonNode m4Payload=payload(event("paymentRegistered","M4",payment.id,m4.id)).path("data"),m7Payload=payload(event("paymentRegistered","M7",payment.id,m7.id)).path("data");
        assertThat(m4Payload.path("amountApplied").decimalValue()).isEqualByComparingTo("40.00");assertThat(m4Payload.path("originId").asText()).isEqualTo("FINE-MIXED");
        assertThat(m7Payload.path("amountApplied").decimalValue()).isEqualByComparingTo("60.00");assertThat(m7Payload.path("originId").asText()).isEqualTo("INF-MIXED");
    }

    @Test void partiallyAllocatedPaymentPublishesAllocationNotPaymentTotal()throws Exception{
        TaxpayerReference taxpayer=taxpayer("PARTIAL-ALLOCATION");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,"INF-PARTIAL-ALLOCATION","100");
        Payment payment=payments.register(request(taxpayer.id,"120",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("70"))));
        assertThat(payload(event("paymentRegistered","M7",payment.id,debt.id)).path("data").path("amountApplied").decimalValue()).isEqualByComparingTo("70.00");
    }

    @Test void laterAllocationPublishesAndSettlesOnlyOnRealTransition()throws Exception{
        TaxpayerReference taxpayer=taxpayer("LATER");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,"INF-LATER","100");Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,null,PaymentMethod.CASH,new BigDecimal("100"),null));
        payments.allocateExisting(payment.id,new ApiDtos.AllocationRequest(debt.id,new BigDecimal("40")));
        assertThat(eventsForPayment("paymentRegistered","M7",payment.id)).hasSize(1);assertThat(events("debtSettled","M7",debt.id)).isEmpty();
        payments.allocateExisting(payment.id,new ApiDtos.AllocationRequest(debt.id,new BigDecimal("60")));
        assertThat(eventsForPayment("paymentRegistered","M7",payment.id)).hasSize(2);assertThat(events("debtSettled","M7",debt.id)).hasSize(1);
    }

    @Test void idempotentRetryDoesNotDuplicateExternalEvents(){
        TaxpayerReference taxpayer=taxpayer("IDEMPOTENT");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,"INF-IDEMPOTENT","100");ApiDtos.RegisterPaymentRequest request=request(taxpayer.id,"100",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("100")));String key="scrum-144-"+UUID.randomUUID();
        Payment first=payments.register(request,key),retry=payments.register(request,key);
        assertThat(retry.id).isEqualTo(first.id);assertThat(eventsForPayment("paymentRegistered","M7",first.id)).hasSize(1);assertThat(events("debtSettled","M7",debt.id)).hasSize(1);
    }

    @Test void authorizedReversalPublishesAffectedExternalAllocation()throws Exception{
        TaxpayerReference taxpayer=taxpayer("REVERSAL");Debt debt=externalDebt(taxpayer,"M4",ExternalObligationType.COMMERCIAL_FINE,"FINE-REVERSAL","100");
        Payment payment=payments.register(request(taxpayer.id,"40",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("40"))));
        PaymentReversalRequest reversal=reversals.request(payment.id,"Reversión autorizada");
        reversals.approve(reversal.id);reversals.execute(reversal.id);
        OutboxEvent event=event("paymentReversed","M4",payment.id,debt.id);
        JsonNode data=payload(event).path("data");
        assertEnvelope(event,payload(event),"paymentReversed","PaymentAllocation");
        assertThat(data.path("originType").asText()).isEqualTo("COMMERCIAL_FINE");
        assertThat(data.path("originId").asText()).isEqualTo("FINE-REVERSAL");
        assertThat(data.has("infractionId")).isFalse();
        assertThat(data.path("reversedAmount").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(data.path("remainingBalance").decimalValue()).isEqualByComparingTo("100.00");
        assertFields(data,"paymentId","debtId","originType","originId","reversedAmount","reversedAt","remainingBalance","reason");
    }

    @Test void authorizedM7ReversalPublishesTrafficReferencesOnlyAfterExecution()throws Exception{
        TaxpayerReference taxpayer=taxpayer("M7-REVERSAL");String infractionId=UUID.randomUUID().toString();
        Debt debt=externalDebt(taxpayer,"transito",ExternalObligationType.TRAFFIC_INFRACTION,infractionId,"100");
        Payment payment=payments.register(request(taxpayer.id,"40",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("40"))));
        PaymentReversalRequest reversal=reversals.request(payment.id,"Reversión autorizada");

        assertThat(eventsForPayment("paymentReversed","CORE",payment.id)).isEmpty();
        reversals.approve(reversal.id);reversals.execute(reversal.id);

        List<OutboxEvent> events=eventsForPayment("paymentReversed","CORE",payment.id);
        assertThat(events).hasSize(1);
        JsonNode data=payload(events.get(0)).path("data");
        assertThat(data.path("originType").asText()).isEqualTo("TRAFFIC_FINE");
        assertThat(data.path("originId").asText()).isEqualTo(infractionId);
        assertThat(data.path("infractionId").asText()).isEqualTo(infractionId);
        assertThat(data.path("reversedAmount").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(data.path("remainingBalance").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(payload(events.get(0)).path("correlationId").asText()).isEqualTo(originalM7Correlation(debt));
        assertFields(data,"paymentId","debtId","originType","originId","infractionId","reversedAmount","reversedAt","remainingBalance","reason");
    }

    @Test void installmentDistributesAndReversesExactSharesAcrossM4AndM7()throws Exception{
        TaxpayerReference taxpayer=taxpayer("PLAN-MIXED");Debt m7=externalDebt(taxpayer,"transito",ExternalObligationType.TRAFFIC_INFRACTION,UUID.randomUUID().toString(),"100");Debt m4=externalDebt(taxpayer,"M4",ExternalObligationType.PERMIT_FEE,"FEE-PLAN-"+UUID.randomUUID(),"100");
        planWorkflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(2,6,new BigDecimal("10"),new BigDecimal("12"),0,0,true,true,2,LocalDate.now().minusDays(1),null,true));
        PaymentPlanRequest request=planWorkflow.request(new ApiDtos.CreatePaymentPlanRequest(taxpayer.id,List.of(m7.id,m4.id),2));
        PaymentPlanRequest granted=planWorkflow.grant(request.id,null);PaymentPlan plan=plans.findById(granted.paymentPlanId).orElseThrow();
        Installment down=installments.findByPaymentPlanIdOrderByNumber(plan.id).get(0);
        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,null,PaymentMethod.CASH,down.totalAmount,List.of(new ApiDtos.AllocationRequest(null,down.id,down.totalAmount))));
        PaymentAllocation recordedAllocation=allocations.findByPaymentId(payment.id).get(0);
        assertThat(auditEntries.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc("PaymentAllocation",recordedAllocation.id.toString()))
            .filteredOn(entry->"PLAN_PRINCIPAL_DISTRIBUTED".equals(entry.action)).hasSize(1);
        JsonNode traffic=payload(event("paymentRegistered","M7",payment.id,m7.id));JsonNode permit=payload(event("paymentRegistered","M4",payment.id,m4.id));
        assertThat(traffic.path("data").path("amountApplied").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(permit.path("data").path("amountApplied").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(traffic.path("correlationId").asText()).isEqualTo(originalM7Correlation(m7));
        assertThat(debts.findById(m7.id).orElseThrow().outstandingBalance).isEqualByComparingTo("90.00");
        PaymentReversalRequest reversal=reversals.request(payment.id,"Cuota revertida");reversals.approve(reversal.id);reversals.execute(reversal.id);
        JsonNode trafficReversal=payload(event("paymentReversed","M7",payment.id,m7.id));JsonNode permitReversal=payload(event("paymentReversed","M4",payment.id,m4.id));
        assertThat(trafficReversal.path("data").path("reversedAmount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(permitReversal.path("data").path("reversedAmount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(trafficReversal.path("correlationId").asText()).isEqualTo(originalM7Correlation(m7));
        assertThat(debts.findById(m7.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
        assertThat(debts.findById(m4.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
        assertThatThrownBy(()->reversals.execute(reversal.id)).isInstanceOf(BusinessException.class);
        assertThat(eventsForPayment("paymentReversed","CORE",payment.id)).hasSize(2);
    }

    @Test void installmentCompletionPreparesPaymentAndSettlementForEachDebt()throws Exception{
        TaxpayerReference taxpayer=taxpayer("PLAN-COMPLETE-M7");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,UUID.randomUUID().toString(),"100");
        planWorkflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(2,6,new BigDecimal("10"),new BigDecimal("12"),0,0,true,true,2,LocalDate.now().minusDays(1),null,true));
        PaymentPlanRequest request=planWorkflow.request(new ApiDtos.CreatePaymentPlanRequest(taxpayer.id,List.of(debt.id),2));
        PaymentPlanRequest granted=planWorkflow.grant(request.id,null);PaymentPlan plan=plans.findById(granted.paymentPlanId).orElseThrow();
        for(Installment installment:installments.findByPaymentPlanIdOrderByNumber(plan.id)){
            Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,null,PaymentMethod.CASH,installment.totalAmount,List.of(new ApiDtos.AllocationRequest(null,installment.id,installment.totalAmount))));
            assertThat(eventsForPayment("paymentRegistered","M7",payment.id)).hasSize(1);
        }
        assertThat(events("debtSettled","M7",debt.id)).hasSize(1);
        assertThat(payload(events("debtSettled","M7",debt.id).get(0)).path("correlationId").asText()).isEqualTo(originalM7Correlation(debt));
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isZero();
    }

    @Test void cancelledHistoricalInstallmentRejectsReversalWithoutEconomicChanges(){
        TaxpayerReference taxpayer=taxpayer("CANCELLED-LEGACY");Debt debt=localDebt(taxpayer,"100");
        PaymentPlan plan=planFor(taxpayer,List.of(debt),2);
        Installment installment=installments.findByPaymentPlanIdOrderByNumber(plan.id).get(0);
        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,null,PaymentMethod.CASH,installment.totalAmount,
            List.of(new ApiDtos.AllocationRequest(null,installment.id,installment.totalAmount))));
        PaymentAllocation allocation=allocations.findByPaymentId(payment.id).get(0);
        removeExactDistribution(allocation);
        Installment cancelled=installments.findById(installment.id).orElseThrow();
        BigDecimal installmentPaid=cancelled.paidAmount;
        cancelled.status=InstallmentStatus.CANCELLED;installments.save(cancelled);
        PaymentPlan expired=plans.findById(plan.id).orElseThrow();expired.status=PaymentPlanStatus.EXPIRED;plans.save(expired);
        BigDecimal balance=debts.findById(debt.id).orElseThrow().outstandingBalance;
        BigDecimal planPaid=plans.findById(plan.id).orElseThrow().paidAmount;
        long outboxBefore=outbox.count();
        PaymentReversalRequest reversal=reversals.request(payment.id,"Cuota histórica cancelada");reversals.approve(reversal.id);

        assertThatThrownBy(()->reversals.execute(reversal.id)).isInstanceOfSatisfying(BusinessException.class,
            error->assertThat(error.code).isEqualTo("INSTALLMENT_CANCELLED"));
        assertThat(installments.findById(installment.id).orElseThrow().status).isEqualTo(InstallmentStatus.CANCELLED);
        assertThat(installments.findById(installment.id).orElseThrow().paidAmount).isEqualByComparingTo(installmentPaid);
        assertThat(plans.findById(plan.id).orElseThrow().paidAmount).isEqualByComparingTo(planPaid);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo(balance);
        assertThat(allocations.findById(allocation.id).orElseThrow().status).isEqualTo("ACTIVE");
        assertThat(paymentRepository.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(reversalRequests.findById(reversal.id).orElseThrow().status).isEqualTo(PaymentReversalStatus.APPROVED);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
        assertThat(auditEntries.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc("PaymentReversal",reversal.id.toString()))
            .filteredOn(entry->"PAYMENT_REVERSAL_BLOCKED".equals(entry.action)).hasSize(1);
    }

    @Test void singleDebtHistoricalInstallmentCanReverseWithoutEstimatedDistribution(){
        TaxpayerReference taxpayer=taxpayer("SINGLE-LEGACY");Debt debt=localDebt(taxpayer,"100");
        PaymentPlan plan=planFor(taxpayer,List.of(debt),2);
        Installment installment=installments.findByPaymentPlanIdOrderByNumber(plan.id).get(0);
        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,null,PaymentMethod.CASH,installment.totalAmount,
            List.of(new ApiDtos.AllocationRequest(null,installment.id,installment.totalAmount))));
        PaymentAllocation allocation=allocations.findByPaymentId(payment.id).get(0);
        removeExactDistribution(allocation);
        BigDecimal balance=debts.findById(debt.id).orElseThrow().outstandingBalance;
        PaymentReversalRequest reversal=reversals.request(payment.id,"Cuota histórica de una deuda");reversals.approve(reversal.id);reversals.execute(reversal.id);

        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo(balance.add(allocation.principalApplied));
        assertThat(allocations.findById(allocation.id).orElseThrow().status).isEqualTo("REVERSED");
        assertThat(eventsForPayment("paymentReversed","CORE",payment.id)).isEmpty();
        assertThatThrownBy(()->reversals.execute(reversal.id)).isInstanceOf(BusinessException.class);
    }

    @Test void expiredPlanRejectsReversalEvenWhenItsInstallmentRemainsPaid(){
        TaxpayerReference taxpayer=taxpayer("EXPIRED-PAID");Debt debt=localDebt(taxpayer,"100");
        PaymentPlan plan=planFor(taxpayer,List.of(debt),2);
        Installment installment=installments.findByPaymentPlanIdOrderByNumber(plan.id).get(0);
        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,null,PaymentMethod.CASH,installment.totalAmount,
            List.of(new ApiDtos.AllocationRequest(null,installment.id,installment.totalAmount))));
        PaymentPlan expired=plans.findById(plan.id).orElseThrow();expired.status=PaymentPlanStatus.EXPIRED;plans.save(expired);
        BigDecimal balance=debts.findById(debt.id).orElseThrow().outstandingBalance;long outboxBefore=outbox.count();
        PaymentReversalRequest reversal=reversals.request(payment.id,"Plan caducado");reversals.approve(reversal.id);

        assertThatThrownBy(()->reversals.execute(reversal.id)).isInstanceOfSatisfying(BusinessException.class,
            error->assertThat(error.code).isEqualTo("PLAN_NOT_REVERSIBLE"));
        assertThat(installments.findById(installment.id).orElseThrow().status).isEqualTo(InstallmentStatus.PAID);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo(balance);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
    }

    @Test void multiDebtHistoricalInstallmentBlocksReversalAndPreservesEvents(){
        TaxpayerReference taxpayer=taxpayer("MULTI-LEGACY");
        Debt traffic=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,UUID.randomUUID().toString(),"100");
        Debt permit=externalDebt(taxpayer,"M4",ExternalObligationType.PERMIT_FEE,"FEE-LEGACY-"+UUID.randomUUID(),"100");
        PaymentPlan plan=planFor(taxpayer,List.of(traffic,permit),2);
        Installment installment=installments.findByPaymentPlanIdOrderByNumber(plan.id).get(0);
        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,null,PaymentMethod.CASH,installment.totalAmount,
            List.of(new ApiDtos.AllocationRequest(null,installment.id,installment.totalAmount))));
        PaymentAllocation allocation=allocations.findByPaymentId(payment.id).get(0);
        removeExactDistribution(allocation);
        BigDecimal trafficBalance=debts.findById(traffic.id).orElseThrow().outstandingBalance;
        BigDecimal permitBalance=debts.findById(permit.id).orElseThrow().outstandingBalance;
        long outboxBefore=outbox.count();
        PaymentReversalRequest reversal=reversals.request(payment.id,"Distribución histórica desconocida");reversals.approve(reversal.id);

        for(int attempt=0;attempt<2;attempt++)assertThatThrownBy(()->reversals.execute(reversal.id))
            .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.code).isEqualTo("HISTORICAL_PLAN_DISTRIBUTION_UNVERIFIABLE"));
        assertThat(debts.findById(traffic.id).orElseThrow().outstandingBalance).isEqualByComparingTo(trafficBalance);
        assertThat(debts.findById(permit.id).orElseThrow().outstandingBalance).isEqualByComparingTo(permitBalance);
        assertThat(allocations.findById(allocation.id).orElseThrow().status).isEqualTo("ACTIVE");
        assertThat(installments.findById(installment.id).orElseThrow().status).isEqualTo(InstallmentStatus.PAID);
        assertThat(paymentRepository.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
        assertThat(eventsForPayment("paymentReversed","CORE",payment.id)).isEmpty();
        assertThat(auditEntries.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc("PaymentReversal",reversal.id.toString()))
            .filteredOn(entry->"PAYMENT_REVERSAL_BLOCKED".equals(entry.action)).hasSize(2);
    }

    @Test void missingHistoricalCorrelationDefersM7EventsWithoutLosingPayment(){
        TaxpayerReference taxpayer=taxpayer("M7-MISSING-CORRELATION");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,UUID.randomUUID().toString(),"100");
        ExternalObligation obligation=obligations.findById(debt.externalObligationId).orElseThrow();
        integrationLogs.delete(integrationLogs.findFirstByEventIdOrderByIdDesc(obligation.sourceEventId).orElseThrow());
        Payment payment=payments.register(request(taxpayer.id,"40",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("40"))));
        assertThat(paymentRepository.findById(payment.id)).isPresent();
        assertThat(eventsForPayment("paymentRegistered","M7",payment.id)).isEmpty();
        PaymentAllocation allocation=allocations.findByPaymentId(payment.id).get(0);
        IntegrationEventLog deferred=integrationLogs.findFirstByBusinessKeyOrderByIdDesc("outbound-deferred:paymentRegistered:PaymentAllocation:"+allocation.id+":Debt:"+debt.id).orElseThrow();
        assertThat(deferred.status).isEqualTo(IntegrationEventStatus.PENDING);
        assertThat(deferred.errorMessage).isEqualTo("M7_ORIGINAL_CORRELATION_ID_UNAVAILABLE");
        assertThat(payloadUnchecked(deferred).path("snapshot").path("data").path("amountApplied").decimalValue()).isEqualByComparingTo("40.00");
    }

    @Test void deferredDirectPaymentsUseCommittedMovementSnapshotsAfterLaterBalanceChanges()throws Exception{
        TaxpayerReference taxpayer=taxpayer("M7-RECONCILE-DIRECT");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,UUID.randomUUID().toString(),"100");
        String original=hideCorrelation(debt);
        Payment first=payments.register(request(taxpayer.id,"40",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("40"))));
        PaymentAllocation firstAllocation=allocations.findByPaymentId(first.id).get(0);
        IntegrationEventLog deferred=economicMarker("paymentRegistered",firstAllocation.id,debt.id);
        assertThat(deferred.status).isEqualTo(IntegrationEventStatus.PENDING);
        assertThat(jdbc.queryForObject("select payload from integration_event_log where id=?",String.class,deferred.id))
            .contains("amountApplied","remainingBalance");
        assertThat(eventsForPayment("paymentRegistered","M7",first.id)).isEmpty();
        DeferredEconomicReconciliation.Result stillPending=economicReconciliation.reconcile(deferred.id);
        assertThat(stillPending.outcome()).isEqualTo("STILL_DEFERRED");
        Payment second=payments.register(request(taxpayer.id,"20",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("20"))));
        PaymentAllocation secondAllocation=allocations.findByPaymentId(second.id).get(0);
        restoreCorrelation(debt,original);

        DeferredEconomicReconciliation.Result prepared=economicReconciliation.reconcile(deferred.id);
        JsonNode firstEvent=payload(outbox.findById(prepared.outboxEventId()).orElseThrow());
        assertThat(prepared.outcome()).isEqualTo("PREPARED_IN_OUTBOX");
        assertThat(prepared.deliveryStatus()).isEqualTo("NOT_CONFIRMED_BY_CORE");
        assertThat(firstEvent.path("correlationId").asText()).isEqualTo(originalM7Correlation(debt));
        assertThat(firstEvent.path("data").path("amountApplied").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(firstEvent.path("data").path("remainingBalance").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("40.00");
        assertThat(economicReconciliation.reconcile(deferred.id).outcome()).isEqualTo("ALREADY_PREPARED");
        assertThat(outbox.findByIdempotencyKey("outbound:paymentRegistered:PaymentAllocation:"+firstAllocation.id+":Debt:"+debt.id)).isPresent();
        assertThat(outbox.findAll().stream().filter(x->x.id.equals(prepared.outboxEventId()))).hasSize(1);
        assertThat(economicReconciliation.reconcile(economicMarker("paymentRegistered",secondAllocation.id,debt.id).id).outcome()).isEqualTo("PREPARED_IN_OUTBOX");
    }

    @Test void deferredTotalPaymentPreparesRegistrationBeforeSettlement()throws Exception{
        TaxpayerReference taxpayer=taxpayer("M7-RECONCILE-SETTLED");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,UUID.randomUUID().toString(),"100");
        String original=hideCorrelation(debt);
        Payment payment=payments.register(request(taxpayer.id,"100",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("100"))));
        PaymentAllocation allocation=allocations.findByPaymentId(payment.id).get(0);
        IntegrationEventLog registered=economicMarker("paymentRegistered",allocation.id,debt.id);
        IntegrationEventLog settled=economicMarker("debtSettled",debt.id,debt.id);
        restoreCorrelation(debt,original);
        assertThat(economicReconciliation.reconcile(settled.id).reason()).isEqualTo("PAYMENT_EVENT_NOT_PREPARED");
        assertThat(economicReconciliation.reconcile(registered.id).outcome()).isEqualTo("PREPARED_IN_OUTBOX");
        DeferredEconomicReconciliation.Result result=economicReconciliation.reconcile(settled.id);
        JsonNode event=payload(outbox.findById(result.outboxEventId()).orElseThrow());
        assertThat(result.outcome()).isEqualTo("PREPARED_IN_OUTBOX");
        assertThat(event.path("data").path("outstandingBalance").decimalValue()).isZero();
        assertThat(event.path("data").path("originId").asText()).isEqualTo(originalM7CorrelationOrigin(debt));
        assertThat(economicReconciliation.reconcile(settled.id).outboxEventId()).isEqualTo(result.outboxEventId());
    }

    @Test void wrongInfractionEvidenceNeverUnlocksDeferredPayment()throws Exception{
        TaxpayerReference taxpayer=taxpayer("M7-WRONG-EVIDENCE");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,UUID.randomUUID().toString(),"90");
        String original=hideCorrelation(debt);
        Payment payment=payments.register(request(taxpayer.id,"30",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("30"))));
        IntegrationEventLog deferred=economicMarker("paymentRegistered",allocations.findByPaymentId(payment.id).get(0).id,debt.id);
        IntegrationEventLog inbound=inbound(debt);ObjectNode wrong=(ObjectNode)json.readTree(original);
        ((ObjectNode)wrong.path("data")).put("infractionId",UUID.randomUUID().toString());inbound.payload=json.writeValueAsString(wrong);integrationLogs.save(inbound);
        assertThat(economicReconciliation.reconcile(deferred.id).outcome()).isEqualTo("STILL_DEFERRED");
        assertThat(eventsForPayment("paymentRegistered","M7",payment.id)).isEmpty();
        restoreCorrelation(debt,original);
        assertThat(economicReconciliation.reconcile(deferred.id).outcome()).isEqualTo("PREPARED_IN_OUTBOX");
    }

    @Test void deferredInstallmentUsesRecordedShareNotPresentDebtBalance()throws Exception{
        TaxpayerReference taxpayer=taxpayer("M7-RECONCILE-PLAN");
        Debt traffic=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,UUID.randomUUID().toString(),"100");
        Debt permit=externalDebt(taxpayer,"M4",ExternalObligationType.PERMIT_FEE,"FEE-"+UUID.randomUUID(),"100");
        String original=hideCorrelation(traffic);PaymentPlan plan=planFor(taxpayer,List.of(traffic,permit),2);
        Installment installment=installments.findByPaymentPlanIdOrderByNumber(plan.id).get(0);
        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,null,PaymentMethod.CASH,installment.totalAmount,
            List.of(new ApiDtos.AllocationRequest(null,installment.id,installment.totalAmount))));
        PaymentAllocation allocation=allocations.findByPaymentId(payment.id).get(0);
        IntegrationEventLog marker=economicMarker("paymentRegistered",allocation.id,traffic.id);
        BigDecimal share=payloadUnchecked(marker).path("snapshot").path("data").path("amountApplied").decimalValue();
        assertThat(share).isPositive();assertThat(event("paymentRegistered","M4",payment.id,permit.id)).isNotNull();
        restoreCorrelation(traffic,original);
        DeferredEconomicReconciliation.Result result=economicReconciliation.reconcile(marker.id);
        assertThat(payload(outbox.findById(result.outboxEventId()).orElseThrow()).path("data").path("amountApplied").decimalValue())
            .isEqualByComparingTo(share);
    }

    @Test void partialAndTotalPaymentReversalsRetainOriginalMovementFacts()throws Exception{
        for(String paid:List.of("40","100")){
            TaxpayerReference taxpayer=taxpayer("M7-RECONCILE-REVERSAL-"+paid);
            Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,UUID.randomUUID().toString(),"100");
            Payment payment=payments.register(request(taxpayer.id,paid,new ApiDtos.AllocationRequest(debt.id,new BigDecimal(paid))));
            PaymentAllocation allocation=allocations.findByPaymentId(payment.id).get(0);
            String original=hideCorrelation(debt);
            PaymentReversalRequest reversal=reversals.request(payment.id,"Reversión controlada");reversals.approve(reversal.id);reversals.execute(reversal.id);
            IntegrationEventLog marker=economicMarker("paymentReversed",allocation.id,debt.id);
            assertThat(eventsForPayment("paymentReversed","M7",payment.id)).isEmpty();
            restoreCorrelation(debt,original);
            DeferredEconomicReconciliation.Result result=economicReconciliation.reconcile(marker.id);
            JsonNode data=payload(outbox.findById(result.outboxEventId()).orElseThrow()).path("data");
            assertThat(data.path("reversedAmount").decimalValue()).isEqualByComparingTo(paid);
            assertThat(data.path("remainingBalance").decimalValue()).isEqualByComparingTo("100.00");
            assertThat(paymentRepository.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.REVERSED);
            assertThat(economicReconciliation.reconcile(marker.id).outboxEventId()).isEqualTo(result.outboxEventId());
        }
    }

    @Test void recoveredCorrelationCannotPrepareReversalBeforeDeferredRegistration()throws Exception{
        TaxpayerReference taxpayer=taxpayer("M7-RECONCILE-ORDER");
        Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,UUID.randomUUID().toString(),"100");
        String original=hideCorrelation(debt);
        Payment payment=payments.register(request(taxpayer.id,"40",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("40"))));
        PaymentAllocation allocation=allocations.findByPaymentId(payment.id).get(0);
        IntegrationEventLog registration=economicMarker("paymentRegistered",allocation.id,debt.id);
        restoreCorrelation(debt,original);
        PaymentReversalRequest reversal=reversals.request(payment.id,"Reversión controlada");
        reversals.approve(reversal.id);reversals.execute(reversal.id);
        IntegrationEventLog reversed=economicMarker("paymentReversed",allocation.id,debt.id);
        assertThat(reversed.errorMessage).isEqualTo("PAYMENT_EVENT_NOT_PREPARED");
        assertThat(eventsForPayment("paymentReversed","M7",payment.id)).isEmpty();
        assertThat(economicReconciliation.reconcile(reversed.id).reason()).isEqualTo("PAYMENT_EVENT_NOT_PREPARED");
        assertThat(economicReconciliation.reconcile(registration.id).outcome()).isEqualTo("PREPARED_IN_OUTBOX");
        assertThat(economicReconciliation.reconcile(reversed.id).outcome()).isEqualTo("PREPARED_IN_OUTBOX");
    }

    @Test void historicalMarkerWithoutMovementSnapshotRequiresManualReview(){
        IntegrationEventLog log=new IntegrationEventLog();log.eventId=UUID.randomUUID();log.externalEventId=log.eventId.toString();
        log.businessKey="outbound-deferred:paymentRegistered:PaymentAllocation:999999";log.eventType="paymentRegistered";
        log.sourceModule="rentas";log.targetModule="CORE";log.direction=EventDirection.OUTBOUND;log.status=IntegrationEventStatus.PENDING;
        log.payload="{\"phase\":\"DEFERRED\",\"reason\":\"M7_ORIGINAL_CORRELATION_ID_UNAVAILABLE\"}";
        log.retryCount=0;log.occurredAt=log.receivedAt=OffsetDateTime.now();integrationLogs.save(log);
        long before=outbox.count();DeferredEconomicReconciliation.Result result=economicReconciliation.reconcile(log.id);
        assertThat(result.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reason()).isEqualTo("HISTORICAL_MOVEMENT_SNAPSHOT_UNAVAILABLE");
        assertThat(outbox.count()).isEqualTo(before);
    }

    @Test void rollbackLeavesNoPaymentAllocationOrOutboxEvent(){
        TaxpayerReference taxpayer=taxpayer("ROLLBACK");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,"INF-ROLLBACK","100");long paymentsBefore=paymentRepository.count(),allocationsBefore=allocations.count(),outboxBefore=outbox.count();
        var request=new ApiDtos.RegisterPaymentRequest(taxpayer.id,PaymentMethod.CASH,new BigDecimal("100"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("40")),new ApiDtos.AllocationRequest(Long.MAX_VALUE,new BigDecimal("60"))));
        assertThatThrownBy(()->payments.register(request)).isInstanceOf(BusinessException.class);
        assertThat(paymentRepository.count()).isEqualTo(paymentsBefore);assertThat(allocations.count()).isEqualTo(allocationsBefore);assertThat(outbox.count()).isEqualTo(outboxBefore);assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
    }

    private ApiDtos.RegisterPaymentRequest request(Long taxpayerId,String amount,ApiDtos.AllocationRequest allocation){return new ApiDtos.RegisterPaymentRequest(taxpayerId,PaymentMethod.CASH,new BigDecimal(amount),List.of(allocation));}
    private IntegrationEventLog inbound(Debt debt){ExternalObligation obligation=obligations.findById(debt.externalObligationId).orElseThrow();
        return integrationLogs.findFirstByEventIdOrderByIdDesc(obligation.sourceEventId).orElseThrow();}
    private String hideCorrelation(Debt debt)throws Exception{IntegrationEventLog source=inbound(debt);String original=source.payload;
        ObjectNode missing=(ObjectNode)json.readTree(original);missing.remove("correlationId");source.payload=json.writeValueAsString(missing);
        integrationLogs.save(source);return original;}
    private void restoreCorrelation(Debt debt,String original){IntegrationEventLog source=inbound(debt);source.payload=original;integrationLogs.save(source);}
    private IntegrationEventLog economicMarker(String type,Long aggregateId,Long debtId){String aggregate="debtSettled".equals(type)?"Debt":"PaymentAllocation";
        return integrationLogs.findFirstByBusinessKeyOrderByIdDesc("outbound-deferred:"+type+":"+aggregate+":"+aggregateId+":Debt:"+debtId).orElseThrow();}
    private String originalM7CorrelationOrigin(Debt debt){return obligations.findById(debt.externalObligationId).orElseThrow().externalReferenceId;}
    private PaymentPlan planFor(TaxpayerReference taxpayer,List<Debt> selected,int count){
        planWorkflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(2,6,new BigDecimal("10"),new BigDecimal("12"),0,0,true,true,2,LocalDate.now().minusDays(1),null,true));
        PaymentPlanRequest request=planWorkflow.request(new ApiDtos.CreatePaymentPlanRequest(taxpayer.id,selected.stream().map(debt->debt.id).toList(),count));
        PaymentPlanRequest granted=planWorkflow.grant(request.id,null);
        return plans.findById(granted.paymentPlanId).orElseThrow();
    }
    private void removeExactDistribution(PaymentAllocation allocation){
        auditEntries.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc("PaymentAllocation",allocation.id.toString()).stream()
            .filter(entry->"PLAN_PRINCIPAL_DISTRIBUTED".equals(entry.action)).forEach(auditEntries::delete);
    }
    private TaxpayerReference taxpayer(String suffix){String unique=suffix+"-"+UUID.randomUUID();return catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"EXT-PAY-"+unique,String.valueOf(Math.abs(unique.hashCode())+10000000L),null,"Contribuyente "+suffix));}
    private Debt externalDebt(TaxpayerReference taxpayer,String source,ExternalObligationType type,String reference,String amount){TaxConcept concept=concept(source);ExternalObligation external=new ExternalObligation();external.sourceModule=source;external.externalType=type;external.externalReferenceId=reference;external.sourceEventId=UUID.randomUUID();external.externalTaxpayerType=taxpayer.taxpayerType;external.externalTaxpayerId=taxpayer.externalId;external.taxpayerId=taxpayer.id;external.taxConceptId=concept.id;external.amount=new BigDecimal(amount);external.dueDate=LocalDate.now().plusDays(10);external.status=ExternalObligationStatus.PROCESSED;external.retryCount=0;external.receivedAt=external.processedAt=OffsetDateTime.now();obligations.save(external);if(type==ExternalObligationType.TRAFFIC_INFRACTION){UUID correlation=UUID.randomUUID();IntegrationEventLog received=new IntegrationEventLog();received.eventId=external.sourceEventId;received.externalEventId=external.sourceEventId.toString();received.eventType="infractionConfirmed";received.sourceModule="M7";received.direction=EventDirection.INBOUND;received.status=IntegrationEventStatus.PROCESSED;received.payload="{\"sourceModule\":\"transito\",\"correlationId\":\""+correlation+"\",\"data\":{\"infractionId\":\""+reference+"\"}}";received.retryCount=0;received.occurredAt=received.receivedAt=OffsetDateTime.now();integrationLogs.save(received);}Debt debt=baseDebt(taxpayer,concept,amount);debt.originType=DebtOriginType.EXTERNAL_OBLIGATION;debt.externalObligationId=external.id;return debts.save(debt);}
    private String originalM7Correlation(Debt debt)throws Exception{ExternalObligation obligation=obligations.findById(debt.externalObligationId).orElseThrow();IntegrationEventLog received=integrationLogs.findFirstByEventIdOrderByIdDesc(obligation.sourceEventId).orElseThrow();return json.readTree(received.payload).path("correlationId").asText();}
    private Debt localDebt(TaxpayerReference taxpayer,String amount){Debt debt=baseDebt(taxpayer,concept("M5"),amount);debt.originType=DebtOriginType.LIQUIDATION;debt.liquidationId=Math.abs(UUID.randomUUID().getMostSignificantBits());return debts.save(debt);}
    private Debt baseDebt(TaxpayerReference taxpayer,TaxConcept concept,String amount){Debt debt=new Debt();debt.taxpayerId=taxpayer.id;debt.taxConceptId=concept.id;debt.originalAmount=debt.currentAmount=debt.outstandingBalance=new BigDecimal(amount).setScale(2);debt.dueDate=LocalDate.now().plusDays(10);debt.status=DebtStatus.PENDING;debt.createdAt=debt.updatedAt=OffsetDateTime.now();return debt;}
    private TaxConcept concept(String origin){String unique="EXT_"+UUID.randomUUID();return catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(unique,"Concepto "+unique,null,TaxConceptType.FEE,origin));}
    private OutboxEvent event(String type,String target,Long paymentId,Long debtId){return eventsForPayment(type,target,paymentId).stream().filter(x->payloadUnchecked(x).path("data").path("debtId").asText().equals("debt-"+debtId)).findFirst().orElseThrow();}
    private List<OutboxEvent> eventsForPayment(String type,String target,Long paymentId){return outbox.findAll().stream().filter(x->type.equals(x.eventType)&&"CORE".equals(x.targetModule)).filter(x->payloadUnchecked(x).path("data").path("paymentId").asText().equals("payment-"+paymentId)).toList();}
    private List<OutboxEvent> events(String type,String target,Long debtId){return outbox.findAll().stream().filter(x->type.equals(x.eventType)&&"CORE".equals(x.targetModule)).filter(x->payloadUnchecked(x).path("data").path("debtId").asText().equals("debt-"+debtId)).toList();}
    private JsonNode payload(OutboxEvent event)throws Exception{return json.readTree(event.payload);}
    private JsonNode payloadUnchecked(OutboxEvent event){try{return payload(event);}catch(Exception ex){throw new AssertionError(ex);}}
    private JsonNode payloadUnchecked(IntegrationEventLog event){try{return json.readTree(event.payload);}catch(Exception ex){throw new AssertionError(ex);}}
    private void assertFields(JsonNode data,String... expected){Set<String> actual=new HashSet<>();data.fieldNames().forEachRemaining(actual::add);assertThat(actual).containsExactlyInAnyOrder(expected);}
    private void assertEnvelope(OutboxEvent event,JsonNode tree,String type,String aggregate){assertThat(tree.path("eventId").asText()).isEqualTo(event.id.toString());assertThat(event.id.version()).isEqualTo(4);assertThat(tree.path("eventType").asText()).isEqualTo(type);assertThat(tree.path("eventVersion").asText()).isEqualTo("1.0");assertThat(tree.path("occurredAt").isTextual()).isTrue();assertThat(tree.path("sourceModule").asText()).isEqualTo("rentas");assertThat(tree.path("correlationId").isTextual()).isTrue();assertThat(tree.path("data").isObject()).isTrue();assertThat(tree.has("payload")).isFalse();assertThat(tree.has("timestamp")).isFalse();assertThat(event.targetModule).isEqualTo("CORE");assertThat(event.aggregateType).isEqualTo(aggregate);assertThat(event.aggregateId).isNotBlank();}
}
