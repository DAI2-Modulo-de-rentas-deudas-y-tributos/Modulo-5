package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("postgres-it")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Testcontainers(disabledWithoutDocker=true)
class MutableResourceEtagHttpTests {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/migration-postgresql,classpath:db/migration-demo");
    }

    @Autowired MockMvc mvc;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired TaxConceptRepository concepts;
    @Autowired TaxConfigurationRepository configurations;
    @Autowired ExternalObligationRepository externalObligations;
    @Autowired DebtRepository debts;
    @Autowired LiquidationRepository liquidations;
    @Autowired PaymentPlanRepository plans;
    @Autowired ExemptionRequestRepository requests;
    @Autowired AdjustmentRepository adjustments;
    @Autowired PaymentRepository payments;
    @Autowired PaymentReversalRepository reversals;
    @Autowired OutboxRepository outbox;

    @Test void mutableDetailsExposeEtagsAndExemptionTransitionsRejectStaleWithoutWriting() throws Exception {
        TaxpayerReference taxpayer=taxpayer();
        TaxConcept concept=concept();
        TaxConfiguration configuration=activeConfiguration(concept);
        Debt debt=debt(taxpayer,concept);
        Liquidation liquidation=liquidation(taxpayer,concept,configuration);
        PaymentPlan plan=plan(taxpayer,debt);
        ExemptionRequest request=exemptionRequest(taxpayer,concept);
        var rentas=actor("rentas-entity-etag","RENTAS");

        for(String path:List.of("/api/v1/debts/"+debt.id,"/api/v1/liquidations/"+liquidation.id,
                "/api/v1/payment-plans/"+plan.id,"/api/v1/exemption-requests/"+request.id)) {
            assertThat(mvc.perform(get(path).with(authentication(rentas))).andReturn().getResponse().getHeader("ETag"))
                .as("ETag del recurso %s",path).isNotBlank();
        }

        String path="/api/v1/exemption-requests/"+request.id;
        String tag=mvc.perform(get(path).with(authentication(rentas))).andReturn().getResponse().getHeader("ETag");
        mvc.perform(post(path+"/start-review").with(authentication(rentas)).header("If-Match","\"stale\""))
            .andExpect(status().isPreconditionFailed()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED")).andExpect(jsonPath("$.traceId").isNotEmpty());
        assertThat(requests.findById(request.id).orElseThrow().status).isEqualTo(ExemptionRequestStatus.PENDING);

        var started=mvc.perform(post(path+"/start-review").with(authentication(rentas)).header("If-Match",tag))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
            .andReturn().getResponse();
        assertThat(started.getHeader("ETag")).isNotBlank().isNotEqualTo(tag);
        assertThat(requests.findById(request.id).orElseThrow().status).isEqualTo(ExemptionRequestStatus.UNDER_REVIEW);
    }

    @Test void staleDebtTagRejectsLateChargeBeforeAnyEconomicWrite() throws Exception {
        TaxpayerReference taxpayer=taxpayer();
        TaxConcept concept=concept();
        Debt debt=debt(taxpayer,concept);
        var rentas=actor("rentas-debt-etag","RENTAS");
        String path="/api/v1/debts/"+debt.id;
        String tag=mvc.perform(get(path).with(authentication(rentas))).andReturn().getResponse().getHeader("ETag");
        assertThat(tag).isNotBlank();

        mvc.perform(post(path+"/late-charges").with(authentication(rentas)).header("If-Match","\"old-debt-tag\"")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isPreconditionFailed()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED")).andExpect(jsonPath("$.traceId").isNotEmpty());
        assertThat(debts.findById(debt.id).orElseThrow().currentAmount).isEqualByComparingTo("100.00");
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
    }

    @Test void staleTaxConfigurationTagRejectsApprovalAndFreshTagReturnsNewVersion() throws Exception {
        TaxConcept concept=concept();
        TaxConfiguration configuration=new TaxConfiguration();configuration.taxConceptId=concept.id;configuration.version=1;
        configuration.calculationType=CalculationType.FIXED;configuration.fixedAmount=new BigDecimal("25.00");
        configuration.partialPaymentAllowed=true;configuration.paymentPlanAllowed=true;configuration.validFrom=LocalDate.now();
        configuration.status=TaxConfigurationStatus.PENDING_APPROVAL;configuration.createdBy="rentas-config-etag";
        configuration.submittedBy="rentas-config-etag";configuration.createdAt=OffsetDateTime.now();
        configuration=configurations.saveAndFlush(configuration);
        var supervisor=actor("supervisor-config-etag","SUPERVISOR");
        String path="/api/v1/tax-configurations/"+configuration.id;
        String tag=mvc.perform(get(path).with(authentication(supervisor))).andReturn().getResponse().getHeader("ETag");
        assertThat(tag).isNotBlank();

        mvc.perform(post(path+"/approve").with(authentication(supervisor)).header("If-Match","\"stale-config-tag\""))
            .andExpect(status().isPreconditionFailed()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED")).andExpect(jsonPath("$.traceId").isNotEmpty());
        assertThat(configurations.findById(configuration.id).orElseThrow().status).isEqualTo(TaxConfigurationStatus.PENDING_APPROVAL);

        var approved=mvc.perform(post(path+"/approve").with(authentication(supervisor)).header("If-Match",tag))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
            .andReturn().getResponse();
        assertThat(approved.getHeader("ETag")).isNotBlank().isNotEqualTo(tag);
        assertThat(configurations.findById(configuration.id).orElseThrow().status).isEqualTo(TaxConfigurationStatus.ACTIVE);
    }

    @Test void staleAdjustmentTagDoesNotChangeDebtOrPublishAndFreshTagApproves() throws Exception {
        TaxpayerReference taxpayer=taxpayer();TaxConcept concept=concept();Debt debt=debt(taxpayer,concept);
        AdjustmentRequest adjustment=new AdjustmentRequest();adjustment.debtId=debt.id;adjustment.type=AdjustmentType.DISCOUNT;
        adjustment.amount=new BigDecimal("10.00");adjustment.reason="ETag adjustment";adjustment.status=AdjustmentStatus.PENDING_APPROVAL;
        adjustment.requestedBy="rentas-adjustment-etag";adjustment.requestedAt=OffsetDateTime.now();adjustment=adjustments.saveAndFlush(adjustment);
        var supervisor=actor("supervisor-adjustment-etag","SUPERVISOR");String path="/api/v1/adjustments/"+adjustment.id;
        String tag=mvc.perform(get(path).with(authentication(supervisor))).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        assertThat(tag).isNotBlank();long eventsBefore=outbox.count();

        mvc.perform(post(path+"/approve").with(authentication(supervisor)).header("If-Match","\"stale-adjustment-tag\"")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        assertThat(adjustments.findById(adjustment.id).orElseThrow().status).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
        assertThat(outbox.count()).isEqualTo(eventsBefore);

        var approved=mvc.perform(post(path+"/approve").with(authentication(supervisor)).header("If-Match",tag)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
            .andReturn().getResponse();
        assertThat(approved.getHeader("ETag")).isNotBlank().isNotEqualTo(tag);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("90.00");
        assertThat(outbox.count()).isEqualTo(eventsBefore+1);
    }

    @Test void staleReversalTagDoesNotExecutePaymentAndFreshTagExecutes() throws Exception {
        TaxpayerReference taxpayer=taxpayer();Payment payment=new Payment();payment.taxpayerId=taxpayer.id;
        payment.paymentMethod=PaymentMethod.CASH;payment.amount=new BigDecimal("25.00");payment.allocatedAmount=BigDecimal.ZERO.setScale(2);
        payment.unallocatedAmount=payment.amount;payment.status=PaymentStatus.CONFIRMED;
        payment.allocationStatus=PaymentAllocationStatus.UNALLOCATED;payment.origin=PaymentOrigin.CASHIER;
        payment.receiptNumber="ETAG-REVERSAL-"+java.util.UUID.randomUUID();payment.registeredBy="test";
        payment.paidAt=payment.createdAt=OffsetDateTime.now();payment=payments.saveAndFlush(payment);
        PaymentReversalRequest reversal=new PaymentReversalRequest();reversal.paymentId=payment.id;reversal.reason="ETag reversal";
        reversal.status=PaymentReversalStatus.APPROVED;reversal.requestedBy="cashier-reversal-etag";
        reversal.requestedAt=OffsetDateTime.now();reversal.resolvedBy="supervisor-reversal-etag";reversal.resolvedAt=OffsetDateTime.now();
        reversal=reversals.saveAndFlush(reversal);
        var rentas=actor("rentas-reversal-etag","RENTAS");String path="/api/v1/payment-reversals/"+reversal.id;
        String tag=mvc.perform(get(path).with(authentication(rentas))).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        assertThat(tag).isNotBlank();long eventsBefore=outbox.count();

        mvc.perform(post(path+"/execute").with(authentication(rentas)).header("If-Match","\"stale-reversal-tag\""))
            .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        assertThat(reversals.findById(reversal.id).orElseThrow().status).isEqualTo(PaymentReversalStatus.APPROVED);
        assertThat(payments.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(outbox.count()).isEqualTo(eventsBefore);

        var executed=mvc.perform(post(path+"/execute").with(authentication(rentas)).header("If-Match",tag))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTED"))
            .andReturn().getResponse();
        assertThat(executed.getHeader("ETag")).isNotBlank().isNotEqualTo(tag);
        assertThat(payments.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.REVERSED);
        assertThat(outbox.count()).isEqualTo(eventsBefore);
    }

    private TaxpayerReference taxpayer(){
        TaxpayerReference taxpayer=new TaxpayerReference();taxpayer.taxpayerType=TaxpayerType.CITIZEN;
        taxpayer.externalId="ETAG-MUTABLE-"+java.util.UUID.randomUUID();taxpayer.displayName="ETag test";
        taxpayer.externalStatus=TaxpayerStatus.ACTIVE;taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();
        return taxpayers.saveAndFlush(taxpayer);
    }

    private TaxConcept concept(){
        TaxConcept concept=new TaxConcept();concept.code="ETAG-"+java.util.UUID.randomUUID();concept.name="ETag concept";
        concept.type=TaxConceptType.FEE;concept.originModule="RENTAS";concept.active=true;
        concept.createdAt=concept.updatedAt=OffsetDateTime.now();return concepts.saveAndFlush(concept);
    }

    private Debt debt(TaxpayerReference taxpayer,TaxConcept concept){
        ExternalObligation source=new ExternalObligation();source.sourceModule="M7";source.externalType=ExternalObligationType.TRAFFIC_INFRACTION;
        source.externalReferenceId="ETAG-OBLIGATION-"+java.util.UUID.randomUUID();source.sourceEventId=java.util.UUID.randomUUID();
        source.externalTaxpayerType=TaxpayerType.CITIZEN;source.externalTaxpayerId=taxpayer.externalId;source.taxpayerId=taxpayer.id;
        source.taxConceptId=concept.id;source.amount=new BigDecimal("100.00");source.dueDate=LocalDate.now().minusDays(30);
        source.status=ExternalObligationStatus.PROCESSED;source.retryCount=0;source.receivedAt=OffsetDateTime.now();
        source=externalObligations.saveAndFlush(source);
        Debt debt=new Debt();debt.taxpayerId=taxpayer.id;debt.taxConceptId=concept.id;debt.originType=DebtOriginType.EXTERNAL_OBLIGATION;debt.externalObligationId=source.id;
        debt.originalAmount=debt.currentAmount=debt.outstandingBalance=new BigDecimal("100.00");
        debt.dueDate=LocalDate.now().minusDays(30);debt.status=DebtStatus.PENDING;debt.createdAt=debt.updatedAt=OffsetDateTime.now();
        return debts.saveAndFlush(debt);
    }

    private TaxConfiguration activeConfiguration(TaxConcept concept){
        TaxConfiguration configuration=new TaxConfiguration();configuration.taxConceptId=concept.id;configuration.version=1;
        configuration.calculationType=CalculationType.FIXED;configuration.fixedAmount=new BigDecimal("25.00");
        configuration.partialPaymentAllowed=true;configuration.paymentPlanAllowed=true;configuration.validFrom=LocalDate.now();
        configuration.status=TaxConfigurationStatus.ACTIVE;configuration.createdBy="rentas-entity-etag";configuration.createdAt=OffsetDateTime.now();
        return configurations.saveAndFlush(configuration);
    }

    private Liquidation liquidation(TaxpayerReference taxpayer,TaxConcept concept,TaxConfiguration configuration){
        Liquidation value=new Liquidation();value.taxpayerId=taxpayer.id;value.taxConceptId=concept.id;value.taxConfigurationId=configuration.id;
        value.configurationVersion=1;value.period="2026-11";value.taxableBase=new BigDecimal("100.00");
        value.baseAmount=value.finalAmount=new BigDecimal("25.00");value.discountAmount=value.exemptionAmount=value.surchargeAmount=value.interestAmount=BigDecimal.ZERO;
        value.dueDate=LocalDate.now().plusDays(30);value.status="ISSUED";value.createdBy="rentas-entity-etag";value.issuedAt=OffsetDateTime.now();
        return liquidations.saveAndFlush(value);
    }

    private PaymentPlan plan(TaxpayerReference taxpayer,Debt debt){
        PaymentPlan plan=new PaymentPlan();plan.taxpayerId=taxpayer.id;plan.debtId=debt.id;
        plan.originalPrincipalAmount=plan.financedPrincipalAmount=plan.totalPlanAmount=plan.outstandingPlanAmount=new BigDecimal("100.00");
        plan.downPaymentAmount=plan.financingInterestAmount=plan.paidAmount=BigDecimal.ZERO;plan.installmentCount=1;plan.refinancingCount=0;
        plan.status=PaymentPlanStatus.ACTIVE;plan.grantedBy="rentas-entity-etag";plan.grantedAt=OffsetDateTime.now();
        return plans.saveAndFlush(plan);
    }

    private ExemptionRequest exemptionRequest(TaxpayerReference taxpayer,TaxConcept concept){
        ExemptionRequest request=new ExemptionRequest();request.taxpayerId=taxpayer.id;request.taxConceptId=concept.id;request.reason="Test ETag";
        request.requestedPercentage=new BigDecimal("25.00");request.requestedFrom=LocalDate.now();request.requestedUntil=LocalDate.now().plusDays(30);
        request.status=ExemptionRequestStatus.PENDING;request.requestedBy="taxpayer-entity-etag";request.requestedAt=OffsetDateTime.now();
        return requests.saveAndFlush(request);
    }

    private UsernamePasswordAuthenticationToken actor(String user,String role){
        return new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(user,null,null),null,
            List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
}
