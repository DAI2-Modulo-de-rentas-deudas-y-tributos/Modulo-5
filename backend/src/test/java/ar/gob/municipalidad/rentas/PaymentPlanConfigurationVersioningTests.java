package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class PaymentPlanConfigurationVersioningTests {
    @Autowired PlanWorkflowService workflow;
    @Autowired PaymentPlanConfigurationRepository configurations;
    @Autowired AuditRepository audit;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired DebtRepository debts;
    @Autowired PaymentPlanRepository plans;
    @Autowired ApiController controller;
    @Autowired ObjectMapper json;

    private PaymentPlanConfiguration v1;

    @BeforeEach void setUp() {
        authenticate();
        v1=workflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(2,6,new BigDecimal("10"),new BigDecimal("5"),3,1,true,true,2,LocalDate.now().minusDays(1),null,true));
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void patchCreatesNewVersionAndLeavesSourceUnchanged() {
        ApiDtos.PaymentPlanConfigurationResponse original=ApiResponses.of(v1);

        PaymentPlanConfiguration v2=workflow.updateConfiguration(v1.id,patch(new BigDecimal("10"),null,null,null,null,null));

        assertThat(v2.id).isNotEqualTo(v1.id);
        assertThat(v2.version).isEqualTo(2);
        assertThat(v2.interestRate).isEqualByComparingTo("10");
        assertThat(v2.minimumInstallments).isEqualTo(v1.minimumInstallments);
        assertThat(v2.maximumInstallments).isEqualTo(v1.maximumInstallments);
        assertThat(v2.minimumDownPaymentPercentage).isEqualByComparingTo(v1.minimumDownPaymentPercentage);
        assertThat(v2.graceDays).isEqualTo(v1.graceDays);
        assertThat(v2.maxOverdueInstallments).isEqualTo(v1.maxOverdueInstallments);
        assertThat(v2.partialInstallmentPaymentAllowed).isEqualTo(v1.partialInstallmentPaymentAllowed);
        assertThat(v2.refinancingAllowed).isEqualTo(v1.refinancingAllowed);
        assertThat(v2.maxRefinancingCount).isEqualTo(v1.maxRefinancingCount);
        assertThat(v2.validFrom).isEqualTo(v1.validFrom);
        assertThat(v2.validUntil).isEqualTo(v1.validUntil);
        assertThat(v2.active).isEqualTo(v1.active);
        assertThat(v2.createdBy).isEqualTo("configuration-tester");
        assertThat(v2.createdAt).isAfterOrEqualTo(v1.createdAt);
        assertThat(ApiResponses.of(configurations.findById(v1.id).orElseThrow())).isEqualTo(original);
        assertThat(controller.planConfiguration(v1.id).interestRate()).isEqualByComparingTo("5");
    }

    @Test void existingPlanKeepsHistoricalConfigurationAndNewPlanUsesNewVersion() {
        Debt first=debt("HISTORICAL-1");
        PaymentPlan oldPlan=grant(first,2);

        PaymentPlanConfiguration v2=workflow.updateConfiguration(v1.id,patch(new BigDecimal("10"),null,false,null,null,null));
        Debt second=debt("HISTORICAL-2");
        PaymentPlan newPlan=grant(second,2);

        assertThat(oldPlan.configurationId).isEqualTo(v1.id);
        assertThat(oldPlan.configurationVersion).isEqualTo(1);
        assertThat(oldPlan.financingInterestAmount).isEqualByComparingTo("4.50");
        assertThat(newPlan.configurationId).isEqualTo(v2.id);
        assertThat(newPlan.configurationVersion).isEqualTo(2);
        assertThat(newPlan.financingInterestAmount).isEqualByComparingTo("9.00");
        assertThatCode(()->workflow.requestRefinancing(oldPlan.id,new ApiDtos.CreateRefinancingRequest(2))).doesNotThrowAnyException();
        assertThatThrownBy(()->workflow.requestRefinancing(newPlan.id,new ApiDtos.CreateRefinancingRequest(2)))
            .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("REFINANCING_NOT_ALLOWED"));
    }

    @Test void inactiveLatestApplicableVersionIsTombstoneAndCanBeReactivated() {
        Debt debt=debt("TOMBSTONE");
        PaymentPlanConfiguration v2=workflow.updateConfiguration(v1.id,patch(null,null,null,null,null,false));

        assertThat(configurations.findById(v1.id).orElseThrow().active).isTrue();
        assertThat(v2.active).isFalse();
        assertThatThrownBy(()->workflow.simulate(simulation(debt)))
            .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("NO_ACTIVE_PLAN_CONFIGURATION"));

        PaymentPlanConfiguration v3=workflow.updateConfiguration(v2.id,patch(null,null,null,null,null,true));
        assertThat(workflow.simulate(simulation(debt)).configurationId()).isEqualTo(v3.id);
        assertThat(configurations.findById(v2.id).orElseThrow().active).isFalse();
    }

    @Test void futureInactiveVersionDoesNotBlockCurrentApplicableVersion() {
        Debt debt=debt("FUTURE-TOMBSTONE");
        PaymentPlanConfiguration future=workflow.updateConfiguration(v1.id,patch(null,null,null,LocalDate.now().plusDays(10),null,false));

        assertThat(future.active).isFalse();
        assertThat(workflow.simulate(simulation(debt)).configurationId()).isEqualTo(v1.id);
    }

    @Test void versionAuditRelatesSourceAndCreatedVersion() throws Exception {
        PaymentPlanConfiguration v2=workflow.updateConfiguration(v1.id,patch(new BigDecimal("7"),2,null,null,null,null));

        List<AuditEntry> entries=audit.findByEntityTypeAndEntityIdOrderByOccurredAt("PaymentPlanConfiguration",v2.id.toString()).stream()
            .filter(x->x.action.equals("PLAN_CONFIGURATION_VERSION_CREATED")).toList();
        assertThat(entries).hasSize(1);
        AuditEntry entry=entries.get(0);
        var payload=json.readTree(entry.newData);
        assertThat(payload.path("sourceConfigurationId").asLong()).isEqualTo(v1.id);
        assertThat(payload.path("sourceVersion").asInt()).isEqualTo(1);
        assertThat(payload.path("newConfigurationId").asLong()).isEqualTo(v2.id);
        assertThat(payload.path("newVersion").asInt()).isEqualTo(2);
    }

    @Test void invalidPatchAndMissingSourceDoNotCreateVersions() {
        long before=configurations.count();

        assertThatThrownBy(()->workflow.updateConfiguration(v1.id,new ApiDtos.UpdatePaymentPlanConfigurationRequest(null,1,null,null,null,null,null,null,null,null,null,null)))
            .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("INVALID_INSTALLMENT_RANGE"));
        assertThatThrownBy(()->workflow.updateConfiguration(Long.MAX_VALUE,patch(new BigDecimal("8"),null,null,null,null,null)))
            .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.status).isEqualTo(404));
        assertThat(configurations.count()).isEqualTo(before);
    }

    private ApiDtos.UpdatePaymentPlanConfigurationRequest patch(BigDecimal rate,Integer overdue,Boolean refinancing,LocalDate from,LocalDate until,Boolean active) {
        return new ApiDtos.UpdatePaymentPlanConfigurationRequest(null,null,null,rate,null,overdue,null,refinancing,null,from,until,active);
    }

    private ApiDtos.PaymentPlanSimulationRequest simulation(Debt debt) { return new ApiDtos.PaymentPlanSimulationRequest(debt.taxpayerId,List.of(debt.id),2); }

    private PaymentPlan grant(Debt debt,int installments) {
        PaymentPlanRequest request=workflow.request(new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),installments));
        workflow.grant(request.id,null);
        return plans.findById(request.paymentPlanId).orElseThrow();
    }

    private Debt debt(String suffix) {
        TaxpayerReference taxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"PLAN-CONFIG-"+suffix,"20"+Math.abs(suffix.hashCode()),null,"Contribuyente "+suffix));
        TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("PLAN_CONFIG_"+suffix,"Concepto "+suffix,null,TaxConceptType.FEE,"M5"));
        TaxConfiguration tax=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));
        catalog.submit(tax.id);catalog.approve(tax.id);
        liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));
        return debts.findByTaxpayerId(taxpayer.id).get(0);
    }

    private void authenticate() {
        var roles=List.of("RENTAS","SUPERVISOR").stream().map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("configuration-tester",null),null,roles));
    }
}
