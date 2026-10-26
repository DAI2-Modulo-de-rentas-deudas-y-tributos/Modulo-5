package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class PaymentPlanFlowTests {
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired PlanWorkflowService workflow;
    @Autowired PaymentService payments;
    @Autowired ReversalService reversals;
    @Autowired DebtRepository debts;
    @Autowired PaymentPlanRepository plans;
    @Autowired PaymentPlanDebtRepository planDebts;
    @Autowired InstallmentRepository installments;
    @Autowired PaymentPlanRequestDebtController requestDebtController;
    @Autowired OutboxRepository outbox;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired ConfirmedIntegrationOutbox integrationOutbox;
    @Autowired DeferredPaymentPlanReconciliation deferredPlanReconciliation;
    @Autowired EntityManager entityManager;

    @BeforeEach void authenticate() {
        var authorities=List.of("RENTAS","SUPERVISOR","CASHIER").stream().map(x->new SimpleGrantedAuthority("ROLE_"+x)).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("tester",null),null,authorities));
        workflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(2,6,new BigDecimal("10"),new BigDecimal("12"),0,0,true,true,2,LocalDate.now().minusDays(1),null,true));
    }

    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void simulationIsPureAndGrantSnapshotsConfiguration() {
        Debt debt=debt("PLAN-FLOW-1","PLAN-FLOW-CONCEPT-1");
        ApiDtos.PaymentPlanSimulationResponse simulation=workflow.simulate(new ApiDtos.PaymentPlanSimulationRequest(debt.taxpayerId,List.of(debt.id),2));
        assertThat(simulation.downPayment()).isEqualByComparingTo("10.00");
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");

        PaymentPlan plan=grant(debt,2);

        assertThat(plan.configurationVersion).isEqualTo(1);
        assertThat(planDebts.findByPaymentPlanId(plan.id)).singleElement();
        assertThat(installments.findByPaymentPlanIdOrderByNumber(plan.id)).extracting(x->x.type)
            .containsExactly(InstallmentType.DOWN_PAYMENT,InstallmentType.REGULAR,InstallmentType.REGULAR);
    }

    @Test void simulationMatchesPersistedInstallmentAndPlanTotalsConserveCentsAfterReversal() {
        Debt debt=debt("PLAN-ROUNDING","PLAN-ROUNDING-CONCEPT","100.01");
        ApiDtos.PaymentPlanSimulationResponse simulation=workflow.simulate(new ApiDtos.PaymentPlanSimulationRequest(debt.taxpayerId,List.of(debt.id),2));
        PaymentPlan plan=grant(debt,2);
        List<Installment> schedule=installments.findByPaymentPlanIdOrderByNumber(plan.id);

        assertThat(simulation.regularInstallmentAmount()).isEqualByComparingTo(schedule.get(1).totalAmount);
        assertThat(schedule.stream().map(x->x.totalAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(plan.totalPlanAmount);
        assertThat(schedule.stream().map(x->x.principalAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(plan.originalPrincipalAmount);
        assertThat(schedule.stream().map(x->x.interestAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(plan.financingInterestAmount);

        Installment firstRegular=schedule.get(1);
        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,null,PaymentMethod.CASH,
            new BigDecimal("5.04"),List.of(new ApiDtos.AllocationRequest(null,firstRegular.id,new BigDecimal("5.04")))));
        PaymentAllocation allocation=paymentsAllocation(payment.id);
        assertThat(allocation.principalApplied.add(allocation.interestApplied)).isEqualByComparingTo(allocation.amount);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("95.51");

        PaymentReversalRequest reversal=reversals.request(payment.id,"Prueba de reversión monetaria");
        reversals.approve(reversal.id);
        reversals.execute(reversal.id);

        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.01");
        assertThat(installments.findById(firstRegular.id).orElseThrow().paidAmount).isEqualByComparingTo("0.00");
    }

    @Test void requestDebtsShowRealConceptAndAreLimitedToTheirOwner() {
        Debt debt=debt("PLAN-REQUEST-DETAIL-OWNER","PLAN-REQUEST-DETAIL-CONCEPT");
        PaymentPlanRequest request=workflow.request(new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2));
        var taxpayerRole=List.of(new SimpleGrantedAuthority("ROLE_TAXPAYER"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("owner",debt.taxpayerId),null,taxpayerRole));

        assertThat(requestDebtController.list(request.id)).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(debt.id);
            assertThat(item.conceptName()).isEqualTo("PLAN-REQUEST-DETAIL-CONCEPT");
            assertThat(item.balanceAtRequest()).isEqualByComparingTo("100.00");
        });

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("other",debt.taxpayerId+1),null,taxpayerRole));
        assertThatThrownBy(()->requestDebtController.list(request.id))
            .isInstanceOfSatisfying(BusinessException.class,exception->assertThat(exception.code).isEqualTo("FORBIDDEN_OWNERSHIP"));
    }

    @Test void installmentPaymentReducesOnlyItsPrincipalAndBlocksDirectDebtPayment() {
        Debt debt=debt("PLAN-FLOW-2","PLAN-FLOW-CONCEPT-2"); PaymentPlan plan=grant(debt,2);
        assertThatThrownBy(()->payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,new BigDecimal("10"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("10"))))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("plan de pago activo");
        Installment down=installments.findByPaymentPlanIdOrderByNumber(plan.id).get(0);

        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,null,PaymentMethod.CASH,down.totalAmount,List.of(new ApiDtos.AllocationRequest(null,down.id,down.totalAmount))));

        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("90.00");
        assertThat(installments.findById(down.id).orElseThrow().status).isEqualTo(InstallmentStatus.PAID);
        PaymentAllocation allocation=paymentsAllocation(payment.id);
        assertThat(allocation.principalApplied.add(allocation.interestApplied)).isEqualByComparingTo(allocation.amount);
        PaymentReversalRequest reversal=reversals.request(payment.id,"Pago duplicado");reversals.approve(reversal.id);reversals.execute(reversal.id);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
        assertThat(installments.findById(down.id).orElseThrow().outstandingAmount).isEqualByComparingTo(down.totalAmount);
    }

    @Test void exceptionalInstallmentCountNeedsSupervisorApproval() {
        Debt debt=debt("PLAN-FLOW-3","PLAN-FLOW-CONCEPT-3");
        PaymentPlanRequest request=workflow.request(new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),8));
        assertThatThrownBy(()->workflow.grant(request.id,null)).isInstanceOf(BusinessException.class).hasMessageContaining("aprobación");

        workflow.submitException(request.id,new ApiDtos.SubmitPlanExceptionRequest("Situación extraordinaria"));
        workflow.approveException(request.id,new ApiDtos.ApprovePlanExceptionRequest("Autorizado"));
        workflow.grant(request.id,null);

        assertThat(request.status).isEqualTo(PaymentPlanRequestStatus.GRANTED);
    }

    @Test void approvedExpirationReleasesDebtsWithoutRestoringPaidPrincipal() throws Exception {
        Debt debt=debt("PLAN-FLOW-4","PLAN-FLOW-CONCEPT-4"); PaymentPlan plan=grant(debt,2);
        installments.findByPaymentPlanIdOrderByNumber(plan.id).forEach(x->x.dueDate=LocalDate.now().minusDays(1));
        assertThat(workflow.defaulted(org.springframework.data.domain.PageRequest.of(0,10))).extracting(x->x.id).contains(plan.id);
        PlanExpirationRequest expiration=workflow.requestExpiration(plan.id,new ApiDtos.CreatePlanExpirationRequest("Cuotas vencidas"));

        workflow.approveExpiration(expiration.id,"Verificado");

        assertThat(plans.findById(plan.id).orElseThrow().status).isEqualTo(PaymentPlanStatus.EXPIRED);
        assertThat(planDebts.findByPaymentPlanId(plan.id)).allMatch(x->x.status==PaymentPlanDebtStatus.RELEASED);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
        OutboxEvent expired=outbox.findAll().stream().filter(event->event.eventType.equals("paymentPlanExpired")&&event.aggregateId.equals(plan.id.toString())).findFirst().orElseThrow();assertThat(expired.targetModule).isEqualTo("PLATFORM");assertThat(json.readTree(expired.payload).path("paymentPlanId").longValue()).isEqualTo(plan.id);
    }

    @Test void refinancingPreservesOldPlanAndCreatesNewOne() throws Exception {
        Debt debt=debt("PLAN-FLOW-5","PLAN-FLOW-CONCEPT-5"); PaymentPlan old=grant(debt,2);
        RefinancingRequest request=workflow.requestRefinancing(old.id,new ApiDtos.CreateRefinancingRequest(3));

        workflow.grantRefinancing(request.id);

        assertThat(plans.findById(old.id).orElseThrow().status).isEqualTo(PaymentPlanStatus.REFINANCED);
        assertThat(request.status).isEqualTo(RefinancingRequestStatus.GRANTED);
        assertThat(plans.findById(request.newPaymentPlanId).orElseThrow().refinancingCount).isEqualTo(1);
        OutboxEvent event=outbox.findAll().stream().filter(item->item.eventType.equals("paymentPlanRefinanced")&&item.aggregateId.equals(request.newPaymentPlanId.toString())).findFirst().orElseThrow();var payload=json.readTree(event.payload);assertThat(event.targetModule).isEqualTo("PLATFORM");assertThat(payload.path("oldPaymentPlanId").longValue()).isEqualTo(old.id);assertThat(payload.path("newPaymentPlanId").longValue()).isEqualTo(request.newPaymentPlanId);
    }

    @Test void completingPlanPublishesItsExistingPlatformEventOnce() throws Exception {Debt debt=debt("PLAN-COMPLETE","PLAN-COMPLETE-CONCEPT");PaymentPlan plan=grant(debt,2);for(Installment installment:installments.findByPaymentPlanIdOrderByNumber(plan.id))payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,null,PaymentMethod.CASH,installment.totalAmount,List.of(new ApiDtos.AllocationRequest(null,installment.id,installment.totalAmount))));assertThat(plans.findById(plan.id).orElseThrow().status).isEqualTo(PaymentPlanStatus.COMPLETED);List<OutboxEvent> events=outbox.findAll().stream().filter(event->event.eventType.equals("paymentPlanCompleted")&&event.aggregateId.equals(plan.id.toString())).toList();assertThat(events).hasSize(1);assertThat(events.get(0).targetModule).isEqualTo("PLATFORM");assertThat(json.readTree(events.get(0).payload).path("paymentPlanId").longValue()).isEqualTo(plan.id);}

    @Test void refinancingRejectsOnlyAnotherActiveRequestForTheSamePlan() {
        Debt debt=debt("PLAN-FLOW-REFINANCING-DUPLICATE","PLAN-FLOW-REFINANCING-DUPLICATE");PaymentPlan plan=grant(debt,2);
        RefinancingRequest first=workflow.requestRefinancing(plan.id,new ApiDtos.CreateRefinancingRequest(3));

        assertThatThrownBy(()->workflow.requestRefinancing(plan.id,new ApiDtos.CreateRefinancingRequest(3)))
            .isInstanceOfSatisfying(BusinessException.class,exception->assertThat(exception.code).isEqualTo("REFINANCING_ALREADY_PENDING"));

        workflow.rejectRefinancing(first.id,"No corresponde");
        RefinancingRequest next=workflow.requestRefinancing(plan.id,new ApiDtos.CreateRefinancingRequest(3));
        assertThat(next.id).isNotEqualTo(first.id);
        assertThat(next.status).isEqualTo(RefinancingRequestStatus.PENDING);
    }

    @Test void planAndRequestIntegrationUuidsSurviveReloadAndUpdates(){
        Debt debt=debt("PLAN-UUID-STABLE","PLAN-UUID-STABLE-CONCEPT");
        PaymentPlanRequest request=workflow.request(new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2));
        UUID requestUuid=request.integrationId;
        entityManager.flush();entityManager.clear();
        PaymentPlanRequest reloadedRequest=entityManager.find(PaymentPlanRequest.class,request.id);
        reloadedRequest.exceptionReason="Actualización local";entityManager.flush();entityManager.clear();
        assertThat(entityManager.find(PaymentPlanRequest.class,request.id).integrationId).isEqualTo(requestUuid);

        workflow.grant(request.id,null);Long planId=entityManager.find(PaymentPlanRequest.class,request.id).paymentPlanId;
        PaymentPlan plan=plans.findById(planId).orElseThrow();UUID planUuid=plan.integrationId;
        entityManager.flush();entityManager.clear();
        PaymentPlan reloadedPlan=entityManager.find(PaymentPlan.class,planId);
        reloadedPlan.refinancingCount=1;entityManager.flush();entityManager.clear();
        assertThat(entityManager.find(PaymentPlan.class,planId).integrationId).isEqualTo(planUuid);
        assertThat(requestUuid).isNotNull();assertThat(planUuid).isNotNull();
    }

    @Test void outboundPlanEventsUsePersistentM5UuidsAndCanonicalCitizenForMultipleDebts() throws Exception {
        Debt first=debt("PLAN-UUID-MULTI-1","PLAN-UUID-MULTI-CONCEPT-1");
        TaxpayerReference taxpayer=taxpayers.findById(first.taxpayerId).orElseThrow();UUID canonical=UUID.randomUUID();taxpayer.canonicalCitizenId=canonical;
        TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("PLAN-UUID-MULTI-CONCEPT-2","Plan UUID segundo",null,TaxConceptType.FEE,"M5"));
        TaxConfiguration config=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("50"),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(config.id);catalog.approve(config.id);
        liquidations.create(new ApiDtos.LiquidationRequest(first.taxpayerId,concept.id,YearMonth.now().plusMonths(1).toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));
        Debt second=debts.findByTaxpayerId(first.taxpayerId).stream().filter(d->d.taxConceptId.equals(concept.id)).findFirst().orElseThrow();
        PaymentPlanRequest request=workflow.request(new ApiDtos.CreatePaymentPlanRequest(first.taxpayerId,List.of(first.id,second.id),2));
        OutboxEvent requested=outbox.findAll().stream().filter(e->e.eventType.equals("paymentPlanRequested")).findFirst().orElseThrow();
        JsonNode envelope=json.readTree(requested.payload),data=envelope.path("data");
        List<String> envelopeFields=new java.util.ArrayList<>();envelope.fieldNames().forEachRemaining(envelopeFields::add);
        assertThat(envelopeFields).containsExactlyInAnyOrder("eventId","eventType","eventVersion","occurredAt","sourceModule","correlationId","causationId","data");
        assertThat(envelope.path("sourceModule").asText()).isEqualTo("rentas");
        assertThat(envelope.path("correlationId").isNull()).isTrue();assertThat(envelope.path("causationId").isNull()).isTrue();
        assertThat(data.path("requestId").asText()).isEqualTo(request.integrationId.toString());
        assertThat(data.path("citizenId").asText()).isEqualTo(canonical.toString());
        assertThat(List.of(data.path("debtIds").get(0).asText(),data.path("debtIds").get(1).asText()))
            .containsExactlyInAnyOrder(first.integrationId.toString(),second.integrationId.toString());
        assertThat(data.path("totalDebt").decimalValue()).isEqualByComparingTo(request.totalDebtAtRequest);
        assertThat(data.path("installments").intValue()).isEqualTo(request.requestedInstallments);
        integrationOutbox.paymentPlanRequested(request);
        assertThat(outbox.findAll().stream().filter(e->e.eventType.equals("paymentPlanRequested")).count()).isEqualTo(1);

        workflow.grant(request.id,null);PaymentPlan plan=plans.findById(request.paymentPlanId).orElseThrow();
        OutboxEvent status=outbox.findAll().stream().filter(e->e.eventType.equals("updatePaymentPlanStatus")).findFirst().orElseThrow();
        JsonNode statusData=json.readTree(status.payload).path("data");
        assertThat(statusData.path("requestId").asText()).isEqualTo(request.integrationId.toString());
        assertThat(statusData.path("citizenId").asText()).isEqualTo(canonical.toString());
        assertThat(statusData.path("planId").asText()).isEqualTo(plan.integrationId.toString());
        assertThat(statusData.path("status").asText()).isEqualTo("GRANTED");
        assertThat(statusData.path("installments").intValue()).isEqualTo(plan.installmentCount);
        assertThat(statusData.path("totalAmount").decimalValue()).isEqualByComparingTo(plan.totalPlanAmount);
        assertThat(status.idempotencyKey).isEqualTo("outbound:updatePaymentPlanStatus:PaymentPlanRequest:"+request.integrationId);
    }

    @Test void rejectionHasNoPlanIdAndDeferredCanonicalIdentityCanBeReconciledOnce() throws Exception {
        Debt debt=debt("PLAN-UUID-DEFERRED","PLAN-UUID-DEFERRED-CONCEPT");
        PaymentPlanRequest request=workflow.request(new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2));
        assertThat(outbox.findAll().stream().noneMatch(e->e.eventType.equals("paymentPlanRequested"))).isTrue();
        assertThat(outbox.findAll().stream().noneMatch(e->e.eventType.equals("updatePaymentPlanStatus"))).isTrue();
        TaxpayerReference taxpayer=taxpayers.findById(debt.taxpayerId).orElseThrow();taxpayer.canonicalCitizenId=UUID.randomUUID();
        deferredPlanReconciliation.reconcile(request.id);
        assertThat(outbox.findAll().stream().filter(e->e.eventType.equals("paymentPlanRequested")).count()).isEqualTo(1);
        deferredPlanReconciliation.reconcile(request.id);
        assertThat(outbox.findAll().stream().filter(e->e.eventType.equals("paymentPlanRequested")).count()).isEqualTo(1);

        PaymentPlanRequest rejected=workflow.request(new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2));
        workflow.rejectRequest(rejected.id,"Deuda no elegible");
        OutboxEvent status=outbox.findAll().stream().filter(e->e.eventType.equals("updatePaymentPlanStatus")&&e.aggregateId.equals(rejected.id.toString())).findFirst().orElseThrow();
        JsonNode data=json.readTree(status.payload).path("data");
        assertThat(data.path("requestId").asText()).isEqualTo(rejected.integrationId.toString());
        assertThat(data.path("status").asText()).isEqualTo("REJECTED");
        assertThat(data.path("reason").asText()).isEqualTo("Deuda no elegible");
        assertThat(data.has("planId")).isFalse();assertThat(data.has("installments")).isFalse();assertThat(data.has("totalAmount")).isFalse();
    }

    private PaymentPlan grant(Debt debt,int count){PaymentPlanRequest request=workflow.request(new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),count));workflow.grant(request.id,null);return plans.findById(request.paymentPlanId).orElseThrow();}
    @Autowired PaymentAllocationRepository paymentAllocations;
    private PaymentAllocation paymentsAllocation(Long paymentId){return paymentAllocations.findByPaymentId(paymentId).get(0);}
    private Debt debt(String externalId,String conceptCode){return debt(externalId,conceptCode,"100");}
    private Debt debt(String externalId,String conceptCode,String amount){TaxpayerReference taxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,externalId,"123",null,"Persona "+externalId));TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(conceptCode,conceptCode,null,TaxConceptType.FEE,"M5"));TaxConfiguration config=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal(amount),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(config.id);catalog.approve(config.id);liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));return debts.findByTaxpayerId(taxpayer.id).get(0);}
}
