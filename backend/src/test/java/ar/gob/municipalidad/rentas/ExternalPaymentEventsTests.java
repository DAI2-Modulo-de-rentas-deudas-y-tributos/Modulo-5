package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:external-payment-events;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class ExternalPaymentEventsTests {
    @Autowired PaymentService payments;@Autowired CatalogService catalog;@Autowired DebtRepository debts;
    @Autowired ExternalObligationRepository obligations;@Autowired PaymentAllocationRepository allocations;
    @Autowired PaymentRepository paymentRepository;@Autowired OutboxRepository outbox;@Autowired ObjectMapper json;

    @BeforeEach void authenticate(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("external-events-test",null),null,List.of(new SimpleGrantedAuthority("ROLE_RENTAS"),new SimpleGrantedAuthority("ROLE_CASHIER"))));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void partialM7PaymentPublishesAllocatedAmountWithoutSettledEvent()throws Exception{
        TaxpayerReference taxpayer=taxpayer("M7-PARTIAL");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,"INF-EXACT-001","100");
        Payment payment=payments.register(request(taxpayer.id,"60",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("60"))));

        OutboxEvent registered=event("paymentRegistered","M7",payment.id,debt.id);JsonNode payload=payload(registered);
        assertEnvelope(registered,payload,"paymentRegistered","PaymentAllocation");
        assertThat(payload.path("payload").path("paymentId").asText()).isEqualTo("payment-"+payment.id);
        assertThat(payload.path("payload").path("debtId").asText()).isEqualTo("debt-"+debt.id);
        assertThat(payload.path("payload").path("externalReferenceId").asText()).isEqualTo("INF-EXACT-001");
        assertThat(payload.path("payload").path("externalType").asText()).isEqualTo("TRAFFIC_INFRACTION");
        assertThat(payload.path("payload").path("amount").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(payload.path("payload").path("remainingBalance").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(events("debtSettled","M7",debt.id)).isEmpty();
    }

    @Test void totalM7PaymentPublishesRegisteredAndSeparateSettledEventsForM7AndM8()throws Exception{
        TaxpayerReference taxpayer=taxpayer("M7-TOTAL");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,"INF-EXACT-002","100");
        Payment payment=payments.register(request(taxpayer.id,"100",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("100"))));

        OutboxEvent registered=event("paymentRegistered","M7",payment.id,debt.id),settledM7=events("debtSettled","M7",debt.id).get(0),settledM8=events("debtSettled","M8",debt.id).get(0);
        assertThat(Set.of(registered.id,settledM7.id,settledM8.id)).hasSize(3);
        JsonNode payload=payload(settledM7);assertEnvelope(settledM7,payload,"debtSettled","Debt");
        assertThat(payload.path("payload").path("externalReferenceId").asText()).isEqualTo("INF-EXACT-002");
        assertThat(payload.path("payload").path("outstandingBalance").decimalValue()).isZero();
    }

    @Test void totalM4PaymentUsesConcreteExternalObligationCorrelation()throws Exception{
        TaxpayerReference taxpayer=taxpayer("M4-TOTAL");Debt debt=externalDebt(taxpayer,"M4",ExternalObligationType.PERMIT_FEE,"FEE-EXACT-001","80");
        Payment payment=payments.register(request(taxpayer.id,"80",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("80"))));

        assertThat(payload(event("paymentRegistered","M4",payment.id,debt.id)).path("payload").path("externalReferenceId").asText()).isEqualTo("FEE-EXACT-001");
        assertThat(payload(events("debtSettled","M4",debt.id).get(0)).path("payload").path("externalReferenceId").asText()).isEqualTo("FEE-EXACT-001");
    }

    @Test void localDebtDoesNotPublishTargetSpecificEvents(){
        TaxpayerReference taxpayer=taxpayer("LOCAL");Debt debt=localDebt(taxpayer,"50");Payment payment=payments.register(request(taxpayer.id,"50",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("50"))));
        assertThat(eventsForPayment("paymentRegistered","M4",payment.id)).isEmpty();assertThat(eventsForPayment("paymentRegistered","M7",payment.id)).isEmpty();
        assertThat(events("debtSettled","M4",debt.id)).isEmpty();assertThat(events("debtSettled","M7",debt.id)).isEmpty();assertThat(events("debtSettled","M8",debt.id)).hasSize(1);
    }

    @Test void mixedPaymentRoutesEachAllocationWithItsOwnAmount()throws Exception{
        TaxpayerReference taxpayer=taxpayer("MIXED");Debt m4=externalDebt(taxpayer,"habilitaciones",ExternalObligationType.COMMERCIAL_FINE,"FINE-MIXED","100");Debt m7=externalDebt(taxpayer,"transito",ExternalObligationType.TRAFFIC_INFRACTION,"INF-MIXED","100");
        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,PaymentMethod.TRANSFER,new BigDecimal("100"),List.of(new ApiDtos.AllocationRequest(m4.id,new BigDecimal("40")),new ApiDtos.AllocationRequest(m7.id,new BigDecimal("60")))));

        JsonNode m4Payload=payload(event("paymentRegistered","M4",payment.id,m4.id)).path("payload"),m7Payload=payload(event("paymentRegistered","M7",payment.id,m7.id)).path("payload");
        assertThat(m4Payload.path("amount").decimalValue()).isEqualByComparingTo("40.00");assertThat(m4Payload.path("externalReferenceId").asText()).isEqualTo("FINE-MIXED");
        assertThat(m7Payload.path("amount").decimalValue()).isEqualByComparingTo("60.00");assertThat(m7Payload.path("externalReferenceId").asText()).isEqualTo("INF-MIXED");
    }

    @Test void partiallyAllocatedPaymentPublishesAllocationNotPaymentTotal()throws Exception{
        TaxpayerReference taxpayer=taxpayer("PARTIAL-ALLOCATION");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,"INF-PARTIAL-ALLOCATION","100");
        Payment payment=payments.register(request(taxpayer.id,"120",new ApiDtos.AllocationRequest(debt.id,new BigDecimal("70"))));
        assertThat(payload(event("paymentRegistered","M7",payment.id,debt.id)).path("payload").path("amount").decimalValue()).isEqualByComparingTo("70.00");
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

    @Test void rollbackLeavesNoPaymentAllocationOrOutboxEvent(){
        TaxpayerReference taxpayer=taxpayer("ROLLBACK");Debt debt=externalDebt(taxpayer,"M7",ExternalObligationType.TRAFFIC_INFRACTION,"INF-ROLLBACK","100");long paymentsBefore=paymentRepository.count(),allocationsBefore=allocations.count(),outboxBefore=outbox.count();
        var request=new ApiDtos.RegisterPaymentRequest(taxpayer.id,PaymentMethod.CASH,new BigDecimal("100"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("40")),new ApiDtos.AllocationRequest(Long.MAX_VALUE,new BigDecimal("60"))));
        assertThatThrownBy(()->payments.register(request)).isInstanceOf(BusinessException.class);
        assertThat(paymentRepository.count()).isEqualTo(paymentsBefore);assertThat(allocations.count()).isEqualTo(allocationsBefore);assertThat(outbox.count()).isEqualTo(outboxBefore);assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
    }

    private ApiDtos.RegisterPaymentRequest request(Long taxpayerId,String amount,ApiDtos.AllocationRequest allocation){return new ApiDtos.RegisterPaymentRequest(taxpayerId,PaymentMethod.CASH,new BigDecimal(amount),List.of(allocation));}
    private TaxpayerReference taxpayer(String suffix){String unique=suffix+"-"+UUID.randomUUID();return catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"EXT-PAY-"+unique,String.valueOf(Math.abs(unique.hashCode())+10000000L),null,"Contribuyente "+suffix));}
    private Debt externalDebt(TaxpayerReference taxpayer,String source,ExternalObligationType type,String reference,String amount){TaxConcept concept=concept(source);ExternalObligation external=new ExternalObligation();external.sourceModule=source;external.externalType=type;external.externalReferenceId=reference;external.sourceEventId=UUID.randomUUID();external.externalTaxpayerType=taxpayer.taxpayerType;external.externalTaxpayerId=taxpayer.externalId;external.taxpayerId=taxpayer.id;external.taxConceptId=concept.id;external.amount=new BigDecimal(amount);external.dueDate=LocalDate.now().plusDays(10);external.status=ExternalObligationStatus.PROCESSED;external.retryCount=0;external.receivedAt=external.processedAt=OffsetDateTime.now();obligations.save(external);Debt debt=baseDebt(taxpayer,concept,amount);debt.originType=DebtOriginType.EXTERNAL_OBLIGATION;debt.externalObligationId=external.id;return debts.save(debt);}
    private Debt localDebt(TaxpayerReference taxpayer,String amount){Debt debt=baseDebt(taxpayer,concept("M5"),amount);debt.originType=DebtOriginType.LIQUIDATION;debt.liquidationId=Math.abs(UUID.randomUUID().getMostSignificantBits());return debts.save(debt);}
    private Debt baseDebt(TaxpayerReference taxpayer,TaxConcept concept,String amount){Debt debt=new Debt();debt.taxpayerId=taxpayer.id;debt.taxConceptId=concept.id;debt.originalAmount=debt.currentAmount=debt.outstandingBalance=new BigDecimal(amount).setScale(2);debt.dueDate=LocalDate.now().plusDays(10);debt.status=DebtStatus.PENDING;debt.createdAt=debt.updatedAt=OffsetDateTime.now();return debt;}
    private TaxConcept concept(String origin){String unique="EXT_"+UUID.randomUUID();return catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(unique,"Concepto "+unique,null,TaxConceptType.FEE,origin));}
    private OutboxEvent event(String type,String target,Long paymentId,Long debtId){return eventsForPayment(type,target,paymentId).stream().filter(x->{try{return payload(x).path("payload").path("debtId").asText().equals("debt-"+debtId);}catch(Exception ex){throw new AssertionError(ex);}}).findFirst().orElseThrow();}
    private List<OutboxEvent> eventsForPayment(String type,String target,Long paymentId){return outbox.findAll().stream().filter(x->type.equals(x.eventType)&&target.equals(x.targetModule)).filter(x->{try{return payload(x).path("payload").path("paymentId").asText().equals("payment-"+paymentId);}catch(Exception ex){throw new AssertionError(ex);}}).toList();}
    private List<OutboxEvent> events(String type,String target,Long debtId){return outbox.findAll().stream().filter(x->type.equals(x.eventType)&&target.equals(x.targetModule)).filter(x->{try{return payload(x).path("payload").path("debtId").asText().equals("debt-"+debtId);}catch(Exception ex){throw new AssertionError(ex);}}).toList();}
    private JsonNode payload(OutboxEvent event)throws Exception{return json.readTree(event.payload);}
    private void assertEnvelope(OutboxEvent event,JsonNode tree,String type,String aggregate){assertThat(tree.path("eventId").asText()).isEqualTo(event.id.toString());assertThat(tree.path("eventType").asText()).isEqualTo(type);assertThat(tree.path("sourceModule").asText()).isEqualTo("rentas");assertThat(event.aggregateType).isEqualTo(aggregate);assertThat(event.aggregateId).isNotBlank();}
}
