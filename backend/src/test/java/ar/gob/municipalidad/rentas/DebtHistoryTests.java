package ar.gob.municipalidad.rentas;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class DebtHistoryTests {
    @Autowired DebtHistoryService history;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired PaymentService payments;
    @Autowired ReversalService reversals;
    @Autowired PaymentPlanService planService;
    @Autowired CreditBalanceService creditService;
    @Autowired AdjustmentService adjustmentService;
    @Autowired DebtRepository debts;
    @Autowired ExternalObligationRepository externalObligations;
    @Autowired CreditBalanceRepository credits;

    @BeforeEach void setUp() {
        authenticate(new AuthenticatedIdentity("tester",null),"RENTAS","SUPERVISOR");
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void debtWithoutMovementsOnlyContainsItsCreation() {
        Debt debt=debt();

        ApiDtos.DebtHistoryResponse response=history.get(debt.id);

        assertThat(response.order()).isEqualTo("ASC");
        assertThat(response.entries()).extracting(ApiDtos.DebtHistoryEntryResponse::type).containsExactly("DEBT_CREATED");
        assertThat(response.origin()).isEqualTo(DebtOriginType.LIQUIDATION);
        assertThat(response.originId()).isEqualTo(debt.liquidationId.toString());
    }

    @Test void paymentAndAllocationAppearInHistory() {
        Debt debt=debt();
        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,new BigDecimal("100"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("100")))));

        ApiDtos.DebtHistoryResponse response=history.get(debt.id);

        assertThat(response.entries()).anySatisfy(entry->{
            assertThat(entry.type()).isEqualTo("PAYMENT_REGISTERED");
            assertThat(entry.referenceId()).isEqualTo(payment.id.toString());
        }).anySatisfy(entry->{
            assertThat(entry.type()).isEqualTo("PAYMENT_ALLOCATION");
            assertThat(entry.relatedReferenceId()).isEqualTo(payment.id.toString());
            assertThat(entry.amount()).isEqualByComparingTo("100.00");
        });
    }

    @Test void partialPaymentKeepsItsExactAmountAndChronologicalOrder() {
        Debt debt=debt();
        payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,new BigDecimal("40"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("40")))));

        ApiDtos.DebtHistoryResponse response=history.get(debt.id);

        assertThat(response.entries()).filteredOn(entry->entry.type().equals("PAYMENT_ALLOCATION")).singleElement()
            .extracting(ApiDtos.DebtHistoryEntryResponse::amount).isEqualTo(new BigDecimal("40.00"));
        assertThat(response.entries()).extracting(ApiDtos.DebtHistoryEntryResponse::date).isSorted();
    }

    @Test void reversalAppearsWithoutLosingTheOriginalAllocation() {
        Debt debt=debt();
        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,new BigDecimal("100"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("100")))));
        PaymentReversalRequest reversal=reversals.request(payment.id,"Pago duplicado");
        reversals.approve(reversal.id);
        reversals.execute(reversal.id);

        ApiDtos.DebtHistoryResponse response=history.get(debt.id);

        assertThat(response.entries()).extracting(ApiDtos.DebtHistoryEntryResponse::type)
            .contains("PAYMENT_ALLOCATION","PAYMENT_ALLOCATION_REVERSED");
    }

    @Test void paymentPlanRelationAppears() {
        Debt debt=debt();
        PaymentPlan plan=planService.grant(new ApiDtos.GrantPlanRequest(debt.id,3,new BigDecimal("10")));

        ApiDtos.DebtHistoryResponse response=history.get(debt.id);

        assertThat(response.entries()).anySatisfy(entry->{
            assertThat(entry.type()).isEqualTo("PAYMENT_PLAN_LINKED");
            assertThat(entry.referenceId()).isEqualTo(plan.id.toString());
            assertThat(entry.status()).isEqualTo(PaymentPlanStatus.ACTIVE.name());
        });
    }

    @Test void creditBalanceApplicationAndAdjustmentAppear() {
        Debt source=debt();
        Debt target=anotherDebt(source.taxpayerId);
        Payment overpayment=payments.register(new ApiDtos.RegisterPaymentRequest(source.taxpayerId,PaymentMethod.CASH,new BigDecimal("120"),List.of(new ApiDtos.AllocationRequest(source.id,new BigDecimal("120")))));
        creditService.apply(credits.findBySourcePaymentId(overpayment.id).orElseThrow().id,new ApiDtos.ApplyCreditBalanceRequest(target.id,new BigDecimal("20")));
        AdjustmentRequest adjustment=adjustmentService.create(new ApiDtos.CreateAdjustmentRequest(target.id,AdjustmentType.SURCHARGE,new BigDecimal("5"),"Actualización"));
        adjustmentService.approve(adjustment.id,"Aprobado");

        ApiDtos.DebtHistoryResponse response=history.get(target.id);

        assertThat(response.entries()).extracting(ApiDtos.DebtHistoryEntryResponse::type)
            .contains("CREDIT_BALANCE_APPLIED","DEBT_ADJUSTMENT_REQUESTED","DEBT_ADJUSTMENT_RESOLVED");
    }

    @Test void externalObligationKeepsItsBusinessOriginId() {
        Debt local=debt();
        ExternalObligation external=new ExternalObligation();
        external.sourceModule="transito";external.externalType=ExternalObligationType.TRAFFIC_INFRACTION;external.externalReferenceId="INF-127";
        external.sourceEventId=UUID.randomUUID();external.externalTaxpayerType=TaxpayerType.CITIZEN;external.externalTaxpayerId="CIT-127";
        external.taxpayerId=local.taxpayerId;external.taxConceptId=local.taxConceptId;external.amount=new BigDecimal("75.00");external.dueDate=LocalDate.now().plusDays(15);
        external.status=ExternalObligationStatus.PROCESSED;external.retryCount=0;external.receivedAt=OffsetDateTime.now().minusMinutes(2);external.processedAt=OffsetDateTime.now().minusMinutes(1);
        externalObligations.save(external);
        Debt debt=new Debt();debt.taxpayerId=local.taxpayerId;debt.taxConceptId=local.taxConceptId;debt.originType=DebtOriginType.EXTERNAL_OBLIGATION;debt.externalObligationId=external.id;
        debt.originalAmount=debt.currentAmount=debt.outstandingBalance=new BigDecimal("75.00");debt.dueDate=external.dueDate;debt.status=DebtStatus.PENDING;debt.createdAt=debt.updatedAt=OffsetDateTime.now();debts.save(debt);

        ApiDtos.DebtHistoryResponse response=history.get(debt.id);

        assertThat(response.origin()).isEqualTo(DebtOriginType.EXTERNAL_OBLIGATION);
        assertThat(response.originId()).isEqualTo("INF-127");
    }

    @Test void taxpayerCannotReadAnotherTaxpayerDebt() {
        Debt debt=debt();
        TaxpayerReference other=taxpayer();
        authenticate(new AuthenticatedIdentity("taxpayer",other.id),"TAXPAYER");

        assertThatThrownBy(()->history.get(debt.id)).isInstanceOfSatisfying(BusinessException.class,exception->{
            assertThat(exception.status).isEqualTo(403);
            assertThat(exception.code).isEqualTo("FORBIDDEN_OWNERSHIP");
        });
    }

    @Test void auditorCanReadHistory() {
        Debt debt=debt();
        authenticate(new AuthenticatedIdentity("auditor",null),"AUDITOR");

        assertThat(history.get(debt.id).debtId()).isEqualTo(debt.id);
    }

    @Test void missingDebtReturnsNotFound() {
        authenticate(new AuthenticatedIdentity("auditor",null),"AUDITOR");

        assertThatThrownBy(()->history.get(999999L)).isInstanceOfSatisfying(BusinessException.class,exception->{
            assertThat(exception.status).isEqualTo(404);
            assertThat(exception.code).isEqualTo("NOT_FOUND");
        });
    }

    private Debt debt() {
        TaxpayerReference taxpayer=taxpayer();
        return createDebt(taxpayer.id);
    }

    private Debt anotherDebt(Long taxpayerId) { return createDebt(taxpayerId); }

    private Debt createDebt(Long taxpayerId) {
        String code="HIST-"+UUID.randomUUID().toString().substring(0,8);
        TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(code,code,null,TaxConceptType.FEE,"M5"));
        TaxConfiguration configuration=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));
        catalog.submit(configuration.id);catalog.approve(configuration.id);
        liquidations.create(new ApiDtos.LiquidationRequest(taxpayerId,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));
        return debts.findByTaxpayerId(taxpayerId).stream().filter(d->d.taxConceptId.equals(concept.id)).findFirst().orElseThrow();
    }

    private TaxpayerReference taxpayer() {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,8);
        return catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"CIT-"+suffix,suffix,null,"Persona "+suffix));
    }

    private void authenticate(AuthenticatedIdentity principal,String... roles) {
        List<SimpleGrantedAuthority> authorities=Arrays.stream(roles).map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,authorities));
    }
}
