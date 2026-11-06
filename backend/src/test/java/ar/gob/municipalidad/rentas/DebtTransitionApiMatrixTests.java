package ar.gob.municipalidad.rentas;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DebtTransitionApiMatrixTests {
    @Autowired MockMvc mvc;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired DebtRepository debts;
    @Autowired PaymentRepository payments;
    @Autowired PaymentAllocationRepository allocations;
    @Autowired PaymentService paymentService;
    @Autowired CreditBalanceRepository credits;
    @Autowired CreditBalanceApplicationRepository creditApplications;
    @Autowired AdjustmentRepository adjustments;
    @Autowired LateChargeApplicationRepository lateChargeApplications;
    @Autowired LateChargeRuleRepository lateChargeRules;
    @Autowired BillRepository bills;
    @Autowired BillDebtRepository billDebts;
    @Autowired PlanWorkflowService planWorkflow;
    @Autowired PaymentPlanRequestRepository planRequests;
    @Autowired PaymentPlanRequestDebtRepository planRequestDebts;
    @Autowired PaymentPlanRepository plans;
    @Autowired OutboxRepository outbox;
    @Autowired DemoUserRepository demoUsers;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired DemoAuthService demoAuth;
    @Autowired FiscalProcessingDatePolicy dates;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @org.junit.jupiter.api.BeforeEach void authenticateFixtureActions() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("state-matrix-rentas",null),null,
            List.of(new SimpleGrantedAuthority("ROLE_RENTAS"),new SimpleGrantedAuthority("ROLE_CASHIER"))));
    }

    @org.junit.jupiter.api.AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest(name = "{0} debt rejects {1} with {2} and no persisted effects")
    @MethodSource("invalidTransitions")
    void invalidDebtTransitionReturnsConflictAndKeepsPersistedState(DebtStatus state, String operation, String code) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        debt.dueDate=today.minusDays(30);
        switch (state) {
            case PAID -> debt.imputar(debt.outstandingBalance);
            case CANCELLED -> debt.anular();
            default -> throw new IllegalArgumentException("La matriz sólo prepara estados terminales");
        }
        debts.saveAndFlush(debt);
        if ("late-charge".equals(operation)) saveLateChargeRule(today);
        if ("plan-request".equals(operation)) createPlanConfiguration(today);
        Payment paymentBefore=null;
        if ("payment-allocation".equals(operation)) {
            paymentBefore=paymentService.register(new ApiDtos.RegisterPaymentRequest(
                debt.taxpayerId,PaymentMethod.CASH,new BigDecimal("10.00"),null));
            payments.flush();
        }
        CreditBalance creditBefore=null;
        if ("credit-application".equals(operation)) {
            Debt source=createDebt(today,debt.taxpayerId);
            Payment sourcePayment=paymentService.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,
                new BigDecimal("120.00"),List.of(new ApiDtos.AllocationRequest(source.id,new BigDecimal("120.00")))));
            creditBefore=credits.findBySourcePaymentId(sourcePayment.id).orElseThrow();
        }

        long paymentsBefore=payments.count();
        long allocationsBefore=allocations.count();
        long adjustmentsBefore=adjustments.count();
        long applicationsBefore=lateChargeApplications.count();
        long creditApplicationsBefore=creditApplications.count();
        long billsBefore=bills.count();
        long billDebtsBefore=billDebts.count();
        long planRequestsBefore=planRequests.count();
        long planRequestDebtsBefore=planRequestDebts.count();
        long outboxBefore=outbox.count();
        BigDecimal currentBefore=debt.currentAmount;
        BigDecimal outstandingBefore=debt.outstandingBalance;
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String session="plan-request".equals(operation)?sessions.token(DemoRole.TAXPAYER,debt.taxpayerId):sessions.token(DemoRole.RENTAS);

        SecurityContextHolder.clearContext();
        mvc.perform(request(debt,operation,session,today,paymentBefore==null?null:paymentBefore.id,
                creditBefore==null?null:creditBefore.id))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(code));

        Debt stored=debts.findById(debt.id).orElseThrow();
        assertThat(stored.status).isEqualTo(state);
        assertThat(stored.currentAmount).isEqualByComparingTo(currentBefore);
        assertThat(stored.outstandingBalance).isEqualByComparingTo(outstandingBefore);
        assertThat(payments.count()).isEqualTo(paymentsBefore);
        if (paymentBefore!=null) {
            Payment storedPayment=payments.findById(paymentBefore.id).orElseThrow();
            assertThat(storedPayment.status).isEqualTo(paymentBefore.status);
            assertThat(storedPayment.allocationStatus).isEqualTo(paymentBefore.allocationStatus);
            assertThat(storedPayment.allocatedAmount).isEqualByComparingTo(paymentBefore.allocatedAmount);
            assertThat(storedPayment.unallocatedAmount).isEqualByComparingTo(paymentBefore.unallocatedAmount);
        }
        if (creditBefore!=null) {
            CreditBalance storedCredit=credits.findById(creditBefore.id).orElseThrow();
            assertThat(storedCredit.status).isEqualTo(creditBefore.status);
            assertThat(storedCredit.availableAmount).isEqualByComparingTo(creditBefore.availableAmount);
        }
        assertThat(allocations.count()).isEqualTo(allocationsBefore);
        assertThat(adjustments.count()).isEqualTo(adjustmentsBefore);
        assertThat(lateChargeApplications.count()).isEqualTo(applicationsBefore);
        assertThat(creditApplications.count()).isEqualTo(creditApplicationsBefore);
        assertThat(bills.count()).isEqualTo(billsBefore);
        assertThat(billDebts.count()).isEqualTo(billDebtsBefore);
        assertThat(planRequests.count()).isEqualTo(planRequestsBefore);
        assertThat(planRequestDebts.count()).isEqualTo(planRequestDebtsBefore);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
    }

    @Test void activePlanDebtRejectsAllocationAdjustmentAndAnotherPlanRequestWithoutPersistingEffects() throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        createPlanConfiguration(today);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String taxpayerSession=sessions.token(DemoRole.TAXPAYER,debt.taxpayerId);
        String rentasSession=sessions.token(DemoRole.RENTAS);
        String body=json.writeValueAsString(new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2));

        SecurityContextHolder.clearContext();
        String firstResponse=mvc.perform(post("/api/v1/payment-plan-requests").header("X-Demo-Session",taxpayerSession)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long firstRequestId=json.readTree(firstResponse).get("id").asLong();
        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/payment-plan-requests/{id}/grant",firstRequestId).header("X-Demo-Session",rentasSession)
                .contentType(MediaType.APPLICATION_JSON).content("{\"downPaymentAmount\":null}"))
            .andExpect(status().isOk());
        assertThat(plans.existsByDebtIdAndStatus(debt.id,PaymentPlanStatus.ACTIVE)).isTrue();
        SecurityContextHolder.clearContext();
        String paymentResponse=mvc.perform(post("/api/v1/payments").header("X-Demo-Session",rentasSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.RegisterPaymentRequest(
                    debt.taxpayerId,PaymentMethod.CASH,new BigDecimal("10.00"),null))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Payment unallocatedPayment=payments.findById(json.readTree(paymentResponse).get("id").asLong()).orElseThrow();

        long requestsBefore=planRequests.count();
        long requestDebtsBefore=planRequestDebts.count();
        long paymentsBefore=payments.count();
        long allocationsBefore=allocations.count();
        long adjustmentsBefore=adjustments.count();
        long outboxBefore=outbox.count();
        BigDecimal currentBefore=debt.currentAmount;
        BigDecimal outstandingBefore=debt.outstandingBalance;

        SecurityContextHolder.clearContext();
        mvc.perform(request(debt,"payment-allocation",rentasSession,today,unallocatedPayment.id,null))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DEBT_IN_ACTIVE_PAYMENT_PLAN"));

        SecurityContextHolder.clearContext();
        mvc.perform(request(debt,"adjustment",rentasSession,today,null,null))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DEBT_IN_ACTIVE_PAYMENT_PLAN"));

        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/payment-plan-requests").header("X-Demo-Session",taxpayerSession)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DEBT_ALREADY_IN_PAYMENT_PLAN"));

        assertThat(planRequests.count()).isEqualTo(requestsBefore);
        assertThat(planRequestDebts.count()).isEqualTo(requestDebtsBefore);
        assertThat(payments.count()).isEqualTo(paymentsBefore);
        assertThat(allocations.count()).isEqualTo(allocationsBefore);
        assertThat(adjustments.count()).isEqualTo(adjustmentsBefore);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
        assertThat(debts.findById(debt.id).orElseThrow()).satisfies(stored->{
            assertThat(stored.status).isEqualTo(DebtStatus.PENDING);
            assertThat(stored.currentAmount).isEqualByComparingTo(currentBefore);
            assertThat(stored.outstandingBalance).isEqualByComparingTo(outstandingBefore);
        });
        Payment storedPayment=payments.findById(unallocatedPayment.id).orElseThrow();
        assertThat(storedPayment.status).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(storedPayment.allocationStatus).isEqualTo(PaymentAllocationStatus.UNALLOCATED);
        assertThat(storedPayment.allocatedAmount).isZero();
        assertThat(storedPayment.unallocatedAmount).isEqualByComparingTo("10.00");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
        Debt debt,String operation,String session,LocalDate today,Long paymentId,Long creditId) throws Exception {
        return switch (operation) {
            case "payment-allocation" -> post("/api/v1/payments/{id}/allocations",paymentId)
                .header("X-Demo-Session",session).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("10.00"))));
            case "adjustment" -> post("/api/v1/adjustments").header("X-Demo-Session",session).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.CreateAdjustmentRequest(debt.id,AdjustmentType.DISCOUNT,
                    new BigDecimal("1.00"),"Transición inválida")));
            case "bill" -> post("/api/v1/bills").header("X-Demo-Session",session).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.CreateBillRequest(debt.taxpayerId,List.of(debt.id),today.plusDays(30))));
            case "credit-application" -> post("/api/v1/credit-balances/{id}/apply",creditId)
                .header("X-Demo-Session",session).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.ApplyCreditBalanceRequest(debt.id,new BigDecimal("10.00"))));
            case "plan-request" -> post("/api/v1/payment-plan-requests").header("X-Demo-Session",session).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2)));
            case "late-charge" -> post("/api/v1/debts/{id}/late-charges",debt.id).header("X-Demo-Session",session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"calculationDate\":\""+today+"\"}");
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private Debt createDebt(LocalDate today) {
        return createDebt(today,null);
    }

    private Debt createDebt(LocalDate today,Long taxpayerId) {
        String suffix=UUID.randomUUID().toString();
        TaxpayerReference taxpayer=taxpayerId==null?catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
            TaxpayerType.CITIZEN,"STATE-MATRIX-"+suffix,suffix.replace("-",""),null,"State matrix test")):
            taxpayers.findById(taxpayerId).orElseThrow();
        TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
            "STATE_MATRIX_"+suffix, "State matrix test", null, TaxConceptType.FEE, "M5"));
        TaxConfiguration configuration=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
            concept.id,CalculationType.FIXED,null,new BigDecimal("100.00"),null,null,true,true,today.minusDays(1),null));
        catalog.submit(configuration.id);
        TestActorSupport.asApprover(()->catalog.approve(configuration.id));
        liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.from(today).toString(),
            BigDecimal.ZERO,today.plusDays(30)));
        return debts.findByTaxpayerId(taxpayer.id).stream().filter(candidate->candidate.taxConceptId.equals(concept.id)).findFirst().orElseThrow();
    }

    private void saveLateChargeRule(LocalDate today) {
        LateChargeRule rule=new LateChargeRule();
        rule.code="STATE_MATRIX_"+UUID.randomUUID();
        rule.surchargeRate=new BigDecimal("1.00");
        rule.dailyInterestRate=new BigDecimal("0.01");
        rule.active=true;
        rule.validFrom=today.minusDays(30);
        lateChargeRules.saveAndFlush(rule);
    }

    private void createPlanConfiguration(LocalDate today) {
        planWorkflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(2,6,
            new BigDecimal("10.00"),new BigDecimal("5.00"),0,2,true,true,2,today.minusDays(1),null,true));
    }

    private static Stream<Arguments> invalidTransitions() {
        return Stream.of(DebtStatus.PAID,DebtStatus.CANCELLED).flatMap(state->Stream.of(
            Arguments.of(state,"payment-allocation","DEBT_NOT_PAYABLE"),
            Arguments.of(state,"adjustment","DEBT_NOT_ADJUSTABLE"),
            Arguments.of(state,"late-charge","DEBT_NOT_CHARGEABLE"),
            Arguments.of(state,"credit-application","DEBT_NOT_PAYABLE"),
            Arguments.of(state,"bill","DEBT_NOT_BILLABLE"),
            Arguments.of(state,"plan-request","DEBT_NOT_ELIGIBLE_FOR_PLAN")));
    }
}
