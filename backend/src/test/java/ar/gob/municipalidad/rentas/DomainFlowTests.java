package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test") @SpringBootTest @Transactional
class DomainFlowTests {
    @Autowired CatalogService catalog; @Autowired LiquidationService liquidationService; @Autowired PaymentService paymentService;
    @Autowired ReversalService reversalService; @Autowired PaymentPlanService planService; @Autowired ExemptionService exemptionService;
    @Autowired ExternalObligationService externalService; @Autowired DebtRepository debts; @Autowired PaymentAllocationRepository allocations;
    @Autowired CreditBalanceRepository credits; @Autowired ProcessedEventRepository processed; @Autowired ExternalObligationRepository obligations; @Autowired LiquidationComponentRepository components;
    @Autowired TaxConceptRepository concepts;

    @BeforeEach void authenticate() {
        var authorities=List.of("RENTAS","SUPERVISOR","CASHIER").stream().map(x->new SimpleGrantedAuthority("ROLE_"+x)).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("tester",null),null,authorities));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void liquidationWithActiveConfigurationCreatesDebtAtomically() {
        TaxpayerReference taxpayer=taxpayer("CIT-1"); TaxConcept concept=concept("SERVICES",TaxConceptType.FEE,"M5"); activateFixed(concept.id,"100.00",true);
        Liquidation l=liquidationService.create(liquidation(taxpayer.id,concept.id));
        assertThat(l.finalAmount).isEqualByComparingTo("100.00");
        assertThat(components.findByLiquidationIdOrderById(l.id)).singleElement().satisfies(x->{assertThat(x.type).isEqualTo(LiquidationComponentType.BASE);assertThat(x.amount).isEqualByComparingTo(l.finalAmount);});
        assertThat(debts.findByTaxpayerId(taxpayer.id)).singleElement().extracting(x->x.outstandingBalance).isEqualTo(new BigDecimal("100.00"));
    }

    @Test void liquidationWithoutActiveConfigurationIsRejected() {
        TaxpayerReference taxpayer=taxpayer("CIT-2"); TaxConcept concept=concept("NO_CONFIG",TaxConceptType.FEE,"M5");
        assertThatThrownBy(()->liquidationService.preview(liquidation(taxpayer.id,concept.id))).isInstanceOf(BusinessException.class).hasMessageContaining("configuración activa");
    }

    @Test void fullPaymentSettlesDebtAndCreatesOutboxAllocation() {
        Debt debt=debt("CIT-3","FULL",true);
        Payment payment=paymentService.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,new BigDecimal("100"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("100")))));
        assertThat(debts.findById(debt.id).orElseThrow().status).isEqualTo(DebtStatus.PAID);
        assertThat(payment.allocationStatus).isEqualTo(PaymentAllocationStatus.FULLY_ALLOCATED);
        assertThat(allocations.findByPaymentId(payment.id)).hasSize(1);
    }

    @Test void forbiddenPartialPaymentRollsBack() {
        Debt debt=debt("CIT-4","NO_PARTIAL",false);
        assertThatThrownBy(()->paymentService.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CARD,new BigDecimal("50"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("50")))))).isInstanceOf(BusinessException.class).hasMessageContaining("pagos parciales");
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
    }

    @Test void overpaymentCreatesCreditAndApprovedReversalRestoresDebt() {
        Debt debt=debt("CIT-5","OVERPAY",true);
        Payment payment=paymentService.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.TRANSFER,new BigDecimal("120"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("120")))));
        assertThat(credits.findBySourcePaymentId(payment.id).orElseThrow().availableAmount).isEqualByComparingTo("20.00");
        assertThat(payment.allocatedAmount.add(payment.unallocatedAmount)).isEqualByComparingTo(payment.amount);
        assertThat(payment.allocatedAmount).isEqualByComparingTo("100.00");
        assertThat(payment.unallocatedAmount).isEqualByComparingTo("20.00");
        assertThat(allocations.findByPaymentId(payment.id)).singleElement().satisfies(a->{
            assertThat(a.principalApplied.add(a.interestApplied)).isEqualByComparingTo(a.amount);
            assertThat(a.principalApplied).isEqualByComparingTo("100.00");
        });
        PaymentReversalRequest request=reversalService.request(payment.id,"Carga duplicada"); reversalService.approve(request.id); reversalService.execute(request.id);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
    }

    @Test void duplicateEventAndBusinessDuplicateCreateSingleDebt() {
        TaxpayerReference taxpayer=taxpayer("EXT-7"); concepts.findByCode("TRAFFIC_INFRACTION").orElseGet(()->concept("TRAFFIC_INFRACTION",TaxConceptType.FINE,"M7"));
        UUID id=UUID.randomUUID(); var data=new ApiDtos.InfractionData("INF-2026-1",TaxpayerType.CITIZEN,"EXT-7",new BigDecimal("75"),LocalDate.now().plusDays(10));
        var first=new ApiDtos.EventEnvelope<>(id,"infractionConfirmed",OffsetDateTime.now(),"M7",data);
        ExternalObligation one=externalService.consumeInfraction(first); ExternalObligation same=externalService.consumeInfraction(first);
        var businessDuplicate=new ApiDtos.EventEnvelope<>(UUID.randomUUID(),"infractionConfirmed",OffsetDateTime.now(),"M7",data);
        ExternalObligation alsoSame=externalService.consumeInfraction(businessDuplicate);
        assertThat(one.id).isEqualTo(same.id).isEqualTo(alsoSame.id); assertThat(debts.findByTaxpayerId(taxpayer.id)).hasSize(1); assertThat(processed.count()).isEqualTo(2);
    }

    @Test void debtCannotBelongToTwoActivePlans() {
        Debt debt=debt("CIT-6","PLAN",true); var request=new ApiDtos.GrantPlanRequest(debt.id,3,new BigDecimal("12"));
        PaymentPlan plan=planService.grant(request); assertThat(plan.installmentCount).isEqualTo(3);
        assertThatThrownBy(()->planService.grant(request)).isInstanceOf(BusinessException.class).hasMessageContaining("plan activo");
    }

    @Test void approvedCurrentExemptionReducesLiquidation() {
        TaxpayerReference taxpayer=taxpayer("CIT-7"); TaxConcept concept=concept("EXEMPT",TaxConceptType.FEE,"M5"); activateFixed(concept.id,"100",true);
        ExemptionRequest request=exemptionService.create(new ApiDtos.CreateExemptionRequest(taxpayer.id,concept.id,"Vulnerabilidad",new BigDecimal("50"),LocalDate.now(),LocalDate.now().plusYears(1)));
        exemptionService.start(request.id); exemptionService.submit(request.id); exemptionService.approve(request.id);
        assertThat(liquidationService.preview(liquidation(taxpayer.id,concept.id)).finalAmount()).isEqualByComparingTo("50.00");
    }

    private TaxpayerReference taxpayer(String externalId){return catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,externalId,"123",null,"Persona "+externalId));}
    private TaxConcept concept(String code,TaxConceptType type,String module){return catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(code,code,null,type,module));}
    private TaxConfiguration activateFixed(Long conceptId,String amount,boolean partial){TaxConfiguration c=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(conceptId,CalculationType.FIXED,null,new BigDecimal(amount),null,null,partial,true,LocalDate.now().minusDays(1),null));catalog.submit(c.id);return catalog.approve(c.id);}
    private ApiDtos.LiquidationRequest liquidation(Long taxpayerId,Long conceptId){return new ApiDtos.LiquidationRequest(taxpayerId,conceptId,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30));}
    private Debt debt(String taxpayerExternal,String code,boolean partial){TaxpayerReference taxpayer=taxpayer(taxpayerExternal);TaxConcept concept=concept(code,TaxConceptType.FEE,"M5");activateFixed(concept.id,"100",partial);liquidationService.create(liquidation(taxpayer.id,concept.id));return debts.findByTaxpayerId(taxpayer.id).get(0);}
}
