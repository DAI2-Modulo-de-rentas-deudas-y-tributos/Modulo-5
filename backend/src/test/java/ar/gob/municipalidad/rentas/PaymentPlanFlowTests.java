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
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class PaymentPlanFlowTests {
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired PlanWorkflowService workflow;
    @Autowired PaymentService payments;
    @Autowired DebtRepository debts;
    @Autowired PaymentPlanRepository plans;
    @Autowired PaymentPlanDebtRepository planDebts;
    @Autowired InstallmentRepository installments;

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

    @Test void installmentPaymentReducesOnlyItsPrincipalAndBlocksDirectDebtPayment() {
        Debt debt=debt("PLAN-FLOW-2","PLAN-FLOW-CONCEPT-2"); PaymentPlan plan=grant(debt,2);
        assertThatThrownBy(()->payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,new BigDecimal("10"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("10"))))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("plan de pago activo");
        Installment down=installments.findByPaymentPlanIdOrderByNumber(plan.id).get(0);

        payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,null,PaymentMethod.CASH,down.totalAmount,List.of(new ApiDtos.AllocationRequest(null,down.id,down.totalAmount))));

        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("90.00");
        assertThat(installments.findById(down.id).orElseThrow().status).isEqualTo(InstallmentStatus.PAID);
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

    @Test void approvedExpirationReleasesDebtsWithoutRestoringPaidPrincipal() {
        Debt debt=debt("PLAN-FLOW-4","PLAN-FLOW-CONCEPT-4"); PaymentPlan plan=grant(debt,2);
        installments.findByPaymentPlanIdOrderByNumber(plan.id).forEach(x->x.dueDate=LocalDate.now().minusDays(1));
        PlanExpirationRequest expiration=workflow.requestExpiration(plan.id,new ApiDtos.CreatePlanExpirationRequest("Cuotas vencidas"));

        workflow.approveExpiration(expiration.id,"Verificado");

        assertThat(plans.findById(plan.id).orElseThrow().status).isEqualTo(PaymentPlanStatus.EXPIRED);
        assertThat(planDebts.findByPaymentPlanId(plan.id)).allMatch(x->x.status==PaymentPlanDebtStatus.RELEASED);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
    }

    @Test void refinancingPreservesOldPlanAndCreatesNewOne() {
        Debt debt=debt("PLAN-FLOW-5","PLAN-FLOW-CONCEPT-5"); PaymentPlan old=grant(debt,2);
        RefinancingRequest request=workflow.requestRefinancing(old.id,new ApiDtos.CreateRefinancingRequest(3));

        workflow.grantRefinancing(request.id);

        assertThat(plans.findById(old.id).orElseThrow().status).isEqualTo(PaymentPlanStatus.REFINANCED);
        assertThat(request.status).isEqualTo(RefinancingRequestStatus.GRANTED);
        assertThat(plans.findById(request.newPaymentPlanId).orElseThrow().refinancingCount).isEqualTo(1);
    }

    private PaymentPlan grant(Debt debt,int count){PaymentPlanRequest request=workflow.request(new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),count));workflow.grant(request.id,null);return plans.findById(request.paymentPlanId).orElseThrow();}
    private Debt debt(String externalId,String conceptCode){TaxpayerReference taxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,externalId,"123",null,"Persona "+externalId));TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(conceptCode,conceptCode,null,TaxConceptType.FEE,"M5"));TaxConfiguration config=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(config.id);catalog.approve(config.id);liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));return debts.findByTaxpayerId(taxpayer.id).get(0);}
}
