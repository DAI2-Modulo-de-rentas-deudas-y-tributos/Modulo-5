package ar.gob.municipalidad.rentas;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    @Autowired PlatformTransactionManager transactionManager;
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
    @Autowired InstallmentRepository installments;
    @Autowired PlanExpirationRepository expirations;
    @Autowired RefinancingRequestRepository refinancings;
    @Autowired ExemptionRequestRepository exemptionRequests;
    @Autowired ExemptionRequestDocumentRepository exemptionDocuments;
    @Autowired ExemptionRepository exemptions;
    @Autowired PaymentReversalRepository reversals;
    @Autowired LiquidationRunRepository liquidationRuns;
    @Autowired LiquidationRunItemRepository liquidationRunItems;
    @Autowired LiquidationRepository liquidationRepository;
    @Autowired LiquidationComponentRepository liquidationComponents;
    @Autowired TaxConfigurationRepository taxConfigurations;
    @Autowired TaxConceptRepository taxConcepts;
    @Autowired OutboxRepository outbox;
    @Autowired AuditRepository audit;
    @Autowired DemoUserRepository demoUsers;
    @Autowired DemoAuthSessionRepository demoAuthSessions;
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

    @ParameterizedTest(name = "POST payments rejects an allocation to {0} debt without persisting effects")
    @EnumSource(value=DebtStatus.class,names={"PAID","CANCELLED"})
    void registerPaymentRejectsAllocationToTerminalDebtAndRollsBackAllEffects(DebtStatus state) throws Exception {
        LocalDate today=dates.today();
        List<UUID> outboxIdsBeforeFixture=outbox.findAll().stream().map(event->event.id).toList();
        List<Long> auditIdsBeforeFixture=audit.findAll().stream().filter(entry->!"SecurityEvent".equals(entry.entityType)).map(entry->entry.id).toList();
        Debt debt=createDebt(today);
        if(state==DebtStatus.PAID)debt.imputar(debt.outstandingBalance);
        else debt.anular();
        debts.saveAndFlush(debt);

        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String rentasSession=sessions.token(DemoRole.RENTAS);
        long paymentsBefore=payments.count();
        long allocationsBefore=allocations.count();
        long outboxBefore=outbox.count();
        BigDecimal currentBefore=debt.currentAmount;
        BigDecimal outstandingBefore=debt.outstandingBalance;
        Long debtId=debt.id;
        Long taxpayerId=debt.taxpayerId;
        Long liquidationId=debt.liquidationId;
        Long configurationId=debt.configurationId;
        Long taxConceptId=debt.taxConceptId;
        DemoAuthSession session=demoAuthSessions.findByTokenHash(DemoAuthService.hashToken(rentasSession)).orElseThrow();
        Long demoUserId=session.demoUserId;

        // Commit only this synthetic H2 fixture so the HTTP request owns its transaction;
        // a failed request must roll back the transient Payment row as it would in production.
        TestTransaction.flagForCommit();
        TestTransaction.end();

        try {
            SecurityContextHolder.clearContext();
            mvc.perform(post("/api/v1/payments").header("X-Demo-Session",rentasSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,
                        PaymentMethod.CASH,new BigDecimal("10.00"),
                        List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("10.00")))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEBT_NOT_PAYABLE"));

            Debt stored=debts.findById(debt.id).orElseThrow();
            assertThat(stored.status).isEqualTo(state);
            assertThat(stored.currentAmount).isEqualByComparingTo(currentBefore);
            assertThat(stored.outstandingBalance).isEqualByComparingTo(outstandingBefore);
            assertThat(payments.count()).isEqualTo(paymentsBefore);
            assertThat(allocations.count()).isEqualTo(allocationsBefore);
            assertThat(outbox.count()).isEqualTo(outboxBefore);
        } finally {
            cleanupTerminalDebtFixture(debtId,taxpayerId,liquidationId,configurationId,taxConceptId,
                session,demoUserId,outboxIdsBeforeFixture);
        }

        assertThat(debts.existsById(debtId)).isFalse();
        assertThat(liquidationComponents.findByLiquidationIdOrderById(liquidationId)).isEmpty();
        assertThat(liquidationRepository.existsById(liquidationId)).isFalse();
        assertThat(taxConfigurations.existsById(configurationId)).isFalse();
        assertThat(taxConcepts.existsById(taxConceptId)).isFalse();
        assertThat(taxpayers.existsById(taxpayerId)).isFalse();
        assertThat(demoAuthSessions.existsById(session.id)).isFalse();
        assertThat(demoUsers.existsById(demoUserId)).isFalse();
        assertThat(outbox.findAll().stream().map(event->event.id).toList()).containsExactlyInAnyOrderElementsOf(outboxIdsBeforeFixture);
        assertThat(audit.findAll().stream().filter(entry->!"SecurityEvent".equals(entry.entityType)).map(entry->entry.id).toList()).containsExactlyInAnyOrderElementsOf(auditIdsBeforeFixture);
    }

    private void cleanupTerminalDebtFixture(Long debtId,Long taxpayerId,Long liquidationId,Long configurationId,
            Long taxConceptId,DemoAuthSession session,Long demoUserId,List<UUID> outboxIdsBeforeFixture) {
        TestTransaction.start();
        debts.deleteById(debtId);
        liquidationComponents.deleteAll(liquidationComponents.findByLiquidationIdOrderById(liquidationId));
        liquidationRepository.deleteById(liquidationId);
        taxConfigurations.deleteById(configurationId);
        taxConcepts.deleteById(taxConceptId);
        taxpayers.deleteById(taxpayerId);
        demoAuthSessions.delete(session);
        demoUsers.deleteById(demoUserId);
        deleteAuditEntries("Debt",debtId);
        deleteAuditEntries("Liquidation",liquidationId);
        deleteAuditEntries("TaxConfiguration",configurationId);
        deleteAuditEntries("TaxConcept",taxConceptId);
        deleteAuditEntries("DemoUser",demoUserId);
        outbox.deleteAll(outbox.findAll().stream().filter(event->!outboxIdsBeforeFixture.contains(event.id)).toList());
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    private void deleteAuditEntries(String entityType,Long entityId) {
        audit.deleteAll(audit.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc(entityType,entityId.toString()));
    }

    @ParameterizedTest(name = "{0} debt in active plan rejects direct allocation and adjustment")
    @EnumSource(value=DebtStatus.class,names={"PENDING","PARTIALLY_PAID"})
    void activePlanDebtRejectsAllocationAdjustmentAndAnotherPlanRequestWithoutPersistingEffects(DebtStatus initialState) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        if(initialState==DebtStatus.PARTIALLY_PAID){
            paymentService.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,
                new BigDecimal("10.00"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("10.00")))));
        }
        assertThat(debt.status).isEqualTo(initialState);
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
            assertThat(stored.status).isEqualTo(initialState);
            assertThat(stored.currentAmount).isEqualByComparingTo(currentBefore);
            assertThat(stored.outstandingBalance).isEqualByComparingTo(outstandingBefore);
        });
        Payment storedPayment=payments.findById(unallocatedPayment.id).orElseThrow();
        assertThat(storedPayment.status).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(storedPayment.allocationStatus).isEqualTo(PaymentAllocationStatus.UNALLOCATED);
        assertThat(storedPayment.allocatedAmount).isZero();
        assertThat(storedPayment.unallocatedAmount).isEqualByComparingTo("10.00");
    }

    @Test void overdueDebtRemainsPayableBecauseDueDateIsNotALifecycleState() throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        debt.dueDate=today.minusDays(1);
        debts.saveAndFlush(debt);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        ApiDtos.RegisterPaymentRequest body=new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,
            new BigDecimal("10.00"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("10.00"))));

        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/payments").header("X-Demo-Session",sessions.token(DemoRole.RENTAS))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isCreated());

        Debt stored=debts.findById(debt.id).orElseThrow();
        assertThat(stored.dueDate).isBefore(today);
        assertThat(stored.status).isEqualTo(DebtStatus.PARTIALLY_PAID);
        assertThat(stored.outstandingBalance).isEqualByComparingTo("90.00");
    }

    @ParameterizedTest(name = "{0} debt in active plan rejects credit application")
    @EnumSource(value=DebtStatus.class,names={"PENDING","PARTIALLY_PAID"})
    void activePlanDebtRejectsCreditApplicationWithoutPersistingEconomicEffects(DebtStatus initialState) throws Exception {
        LocalDate today=dates.today();
        Debt planDebt=createDebt(today);
        if(initialState==DebtStatus.PARTIALLY_PAID){
            paymentService.register(new ApiDtos.RegisterPaymentRequest(planDebt.taxpayerId,PaymentMethod.CASH,
                new BigDecimal("10.00"),List.of(new ApiDtos.AllocationRequest(planDebt.id,new BigDecimal("10.00")))));
        }
        assertThat(planDebt.status).isEqualTo(initialState);
        Debt creditSource=createDebt(today,planDebt.taxpayerId);
        createPlanConfiguration(today);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String taxpayerSession=sessions.token(DemoRole.TAXPAYER,planDebt.taxpayerId);
        String rentasSession=sessions.token(DemoRole.RENTAS);
        String requestBody=json.writeValueAsString(new ApiDtos.CreatePaymentPlanRequest(
            planDebt.taxpayerId,List.of(planDebt.id),2));

        SecurityContextHolder.clearContext();
        String requestResponse=mvc.perform(post("/api/v1/payment-plan-requests").header("X-Demo-Session",taxpayerSession)
                .contentType(MediaType.APPLICATION_JSON).content(requestBody))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long requestId=json.readTree(requestResponse).get("id").asLong();

        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/payment-plan-requests/{id}/grant",requestId).header("X-Demo-Session",rentasSession)
                .contentType(MediaType.APPLICATION_JSON).content("{\"downPaymentAmount\":null}"))
            .andExpect(status().isOk());
        PaymentPlan plan=plans.findById(planRequests.findById(requestId).orElseThrow().paymentPlanId).orElseThrow();
        assertThat(plan.status).isEqualTo(PaymentPlanStatus.ACTIVE);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("state-matrix-rentas",null),null,
            List.of(new SimpleGrantedAuthority("ROLE_RENTAS"),new SimpleGrantedAuthority("ROLE_CASHIER"))));
        Payment sourcePayment=paymentService.register(new ApiDtos.RegisterPaymentRequest(planDebt.taxpayerId,
            PaymentMethod.CASH,new BigDecimal("120.00"),List.of(new ApiDtos.AllocationRequest(creditSource.id,new BigDecimal("100.00")))));
        CreditBalance credit=credits.findBySourcePaymentId(sourcePayment.id).orElseThrow();
        assertThat(credit.availableAmount).isEqualByComparingTo("20.00");

        Debt beforeDebt=debts.findById(planDebt.id).orElseThrow();
        CreditBalance beforeCredit=credits.findById(credit.id).orElseThrow();
        long applicationsBefore=creditApplications.count();
        long paymentsBefore=payments.count();
        long allocationsBefore=allocations.count();
        long outboxBefore=outbox.count();

        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/credit-balances/{id}/apply",credit.id).header("X-Demo-Session",rentasSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.ApplyCreditBalanceRequest(planDebt.id,new BigDecimal("10.00")))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DEBT_IN_ACTIVE_PAYMENT_PLAN"));

        assertThat(debts.findById(planDebt.id).orElseThrow()).satisfies(stored->{
            assertThat(stored.status).isEqualTo(beforeDebt.status);
            assertThat(stored.currentAmount).isEqualByComparingTo(beforeDebt.currentAmount);
            assertThat(stored.outstandingBalance).isEqualByComparingTo(beforeDebt.outstandingBalance);
        });
        assertThat(credits.findById(credit.id).orElseThrow()).satisfies(stored->{
            assertThat(stored.status).isEqualTo(beforeCredit.status);
            assertThat(stored.originalAmount).isEqualByComparingTo(beforeCredit.originalAmount);
            assertThat(stored.availableAmount).isEqualByComparingTo(beforeCredit.availableAmount);
        });
        assertThat(creditApplications.count()).isEqualTo(applicationsBefore);
        assertThat(payments.count()).isEqualTo(paymentsBefore);
        assertThat(allocations.count()).isEqualTo(allocationsBefore);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
    }

    @ParameterizedTest(name = "GRANTED plan request rejects {0} with 409")
    @MethodSource("invalidGrantedPlanRequestTransitions")
    void grantedPlanRequestRejectsFurtherTransitionsAndKeepsItsState(String action,DemoRole actor,Object body) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        createPlanConfiguration(today);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String taxpayer=sessions.token(DemoRole.TAXPAYER,debt.taxpayerId);
        String rentas=sessions.token(DemoRole.RENTAS);
        String requestBody=json.writeValueAsString(new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2));

        SecurityContextHolder.clearContext();
        String response=performAsSession(post("/api/v1/payment-plan-requests").header("X-Demo-Session",taxpayer)
                .contentType(MediaType.APPLICATION_JSON).content(requestBody))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long requestId=json.readTree(response).get("id").asLong();
        String grantBody=json.writeValueAsString(new ApiDtos.GrantPaymentPlanRequest(null));

        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/payment-plan-requests/{id}/grant",requestId).header("X-Demo-Session",rentas)
                .contentType(MediaType.APPLICATION_JSON).content(grantBody))
            .andExpect(status().isOk());
        long plansAfterGrant=plans.count();

        String actionBody=json.writeValueAsString(body);
        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/payment-plan-requests/{id}/"+action,requestId)
                .header("X-Demo-Session",sessions.token(actor))
                .contentType(MediaType.APPLICATION_JSON).content(actionBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_PLAN_REQUEST_TRANSITION"));

        assertThat(planRequests.findById(requestId).orElseThrow().status).isEqualTo(PaymentPlanRequestStatus.GRANTED);
        assertThat(plans.count()).isEqualTo(plansAfterGrant);
    }

    private static Stream<Arguments> invalidGrantedPlanRequestTransitions() {
        return Stream.of(
            Arguments.of("grant",DemoRole.RENTAS,new ApiDtos.GrantPaymentPlanRequest(null)),
            Arguments.of("reject",DemoRole.RENTAS,new ApiDtos.RejectionRequest("transition already resolved")),
            Arguments.of("submit-exception",DemoRole.RENTAS,new ApiDtos.SubmitPlanExceptionRequest("exception")),
            Arguments.of("approve-exception",DemoRole.SUPERVISOR,new ApiDtos.ApprovePlanExceptionRequest(null)),
            Arguments.of("reject-exception",DemoRole.SUPERVISOR,new ApiDtos.RejectionRequest("transition already resolved"))
        );
    }

    @ParameterizedTest(name = "{0} plan request rejects {1} with 409")
    @MethodSource("invalidOtherPlanRequestTransitions")
    void nonGrantedPlanRequestRejectsInvalidLifecycleAction(PaymentPlanRequestStatus state,String action,DemoRole actor,Object body) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        createPlanConfiguration(today);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String taxpayer=sessions.token(DemoRole.TAXPAYER,debt.taxpayerId);
        String rentas=sessions.token(DemoRole.RENTAS);
        String response=performAsSession(post("/api/v1/payment-plan-requests").header("X-Demo-Session",taxpayer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                    new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long requestId=json.readTree(response).get("id").asLong();

        if(state==PaymentPlanRequestStatus.PENDING_EXCEPTION_APPROVAL) {
            performAsSession(post("/api/v1/payment-plan-requests/{id}/submit-exception",requestId).header("X-Demo-Session",rentas)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ApiDtos.SubmitPlanExceptionRequest("exception"))))
                .andExpect(status().isOk());
        } else if(state==PaymentPlanRequestStatus.REJECTED) {
            performAsSession(post("/api/v1/payment-plan-requests/{id}/reject",requestId).header("X-Demo-Session",rentas)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ApiDtos.RejectionRequest("rejected"))))
                .andExpect(status().isOk());
        }

        long requestsBefore=planRequests.count();
        long plansBefore=plans.count();
        long requestDebtsBefore=planRequestDebts.count();
        long outboxBefore=outbox.count();
        String beforeStatus=planRequests.findById(requestId).orElseThrow().status.name();
        var call=post("/api/v1/payment-plan-requests/{id}/"+action,requestId)
            .header("X-Demo-Session",sessions.token(actor))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        performAsSession(call).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_PLAN_REQUEST_TRANSITION"));

        assertThat(planRequests.findById(requestId).orElseThrow().status.name()).isEqualTo(beforeStatus);
        assertThat(planRequests.count()).isEqualTo(requestsBefore);
        assertThat(plans.count()).isEqualTo(plansBefore);
        assertThat(planRequestDebts.count()).isEqualTo(requestDebtsBefore);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
    }

    private static Stream<Arguments> invalidOtherPlanRequestTransitions() {
        return Stream.of(
            Arguments.of(PaymentPlanRequestStatus.PENDING,"approve-exception",DemoRole.SUPERVISOR,new ApiDtos.ApprovePlanExceptionRequest(null)),
            Arguments.of(PaymentPlanRequestStatus.PENDING,"reject-exception",DemoRole.SUPERVISOR,new ApiDtos.RejectionRequest("not pending exception")),
            Arguments.of(PaymentPlanRequestStatus.PENDING_EXCEPTION_APPROVAL,"submit-exception",DemoRole.RENTAS,new ApiDtos.SubmitPlanExceptionRequest("again")),
            Arguments.of(PaymentPlanRequestStatus.REJECTED,"grant",DemoRole.RENTAS,new ApiDtos.GrantPaymentPlanRequest(null)),
            Arguments.of(PaymentPlanRequestStatus.REJECTED,"reject",DemoRole.RENTAS,new ApiDtos.RejectionRequest("again")),
            Arguments.of(PaymentPlanRequestStatus.REJECTED,"submit-exception",DemoRole.RENTAS,new ApiDtos.SubmitPlanExceptionRequest("again")),
            Arguments.of(PaymentPlanRequestStatus.REJECTED,"approve-exception",DemoRole.SUPERVISOR,new ApiDtos.ApprovePlanExceptionRequest(null)),
            Arguments.of(PaymentPlanRequestStatus.REJECTED,"reject-exception",DemoRole.SUPERVISOR,new ApiDtos.RejectionRequest("again"))
        );
    }

    @ParameterizedTest(name = "{0} payment plan rejects {1} with 409")
    @MethodSource("invalidPaymentPlanTransitions")
    void nonActivePaymentPlanRejectsStateDependentActionWithoutEffects(PaymentPlanStatus state,String action) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        createPlanConfiguration(today);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String taxpayer=sessions.token(DemoRole.TAXPAYER,debt.taxpayerId);
        String rentas=sessions.token(DemoRole.RENTAS);
        String response=performAsSession(post("/api/v1/payment-plan-requests").header("X-Demo-Session",taxpayer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                    new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long requestId=json.readTree(response).get("id").asLong();
        performAsSession(post("/api/v1/payment-plan-requests/{id}/grant",requestId).header("X-Demo-Session",rentas)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ApiDtos.GrantPaymentPlanRequest(null))))
            .andExpect(status().isOk());
        PaymentPlan plan=plans.findById(planRequests.findById(requestId).orElseThrow().paymentPlanId).orElseThrow();
        if(state!=PaymentPlanStatus.ACTIVE) {
            // Seed persisted status to isolate each API guard. COMPLETED, EXPIRED and REFINANCED
            // have lifecycle flows; CANCELLED has no public status transition endpoint in this version.
            plan.status=state;
            plans.saveAndFlush(plan);
        }
        Long installmentId=installments.findByPaymentPlanIdOrderByNumber(plan.id).get(0).id;
        long planCountBefore=plans.count();
        long allocationCountBefore=allocations.count();
        long expirationCountBefore=expirations.count();
        long refinancingCountBefore=refinancings.count();
        long outboxBefore=outbox.count();
        BigDecimal balanceBefore=plan.outstandingPlanAmount;
        String beforeStatus=plan.status.name();

        if("expiration".equals(action)) {
            performAsSession(post("/api/v1/payment-plans/{planId}/expiration-requests",plan.id)
                    .header("X-Demo-Session",rentas).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new ApiDtos.CreatePlanExpirationRequest("matrix"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_NOT_ACTIVE"));
        } else if("installment-payment".equals(action)) {
            var paymentRequest=post("/api/v1/payments").header("X-Demo-Session",rentas).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,
                    new BigDecimal("1.00"),List.of(new ApiDtos.AllocationRequest(null,installmentId,new BigDecimal("1.00"))))));
            performAsSessionAndAssertRollbackOnly(paymentRequest,"INSTALLMENT_NOT_PAYABLE");
        } else if("refinancing".equals(action)) {
            performAsSession(post("/api/v1/payment-plans/{planId}/refinancing-requests",plan.id)
                    .header("X-Demo-Session",taxpayer).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new ApiDtos.CreateRefinancingRequest(2))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_NOT_REFINANCING_ELIGIBLE"));
        } else {
            throw new IllegalArgumentException(action);
        }

        PaymentPlan stored=plans.findById(plan.id).orElseThrow();
        assertThat(stored.status.name()).isEqualTo(beforeStatus);
        assertThat(stored.outstandingPlanAmount).isEqualByComparingTo(balanceBefore);
        assertThat(plans.count()).isEqualTo(planCountBefore);
        assertThat(allocations.count()).isEqualTo(allocationCountBefore);
        assertThat(expirations.count()).isEqualTo(expirationCountBefore);
        assertThat(refinancings.count()).isEqualTo(refinancingCountBefore);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
    }

    private static Stream<Arguments> invalidPaymentPlanTransitions() {
        return Stream.of(
            Arguments.of(PaymentPlanStatus.COMPLETED,"expiration"),
            Arguments.of(PaymentPlanStatus.EXPIRED,"expiration"),
            Arguments.of(PaymentPlanStatus.REFINANCED,"expiration"),
            Arguments.of(PaymentPlanStatus.CANCELLED,"expiration"),
            Arguments.of(PaymentPlanStatus.COMPLETED,"installment-payment"),
            Arguments.of(PaymentPlanStatus.EXPIRED,"installment-payment"),
            Arguments.of(PaymentPlanStatus.REFINANCED,"installment-payment"),
            Arguments.of(PaymentPlanStatus.CANCELLED,"installment-payment"),
            Arguments.of(PaymentPlanStatus.COMPLETED,"refinancing"),
            Arguments.of(PaymentPlanStatus.REFINANCED,"refinancing"),
            Arguments.of(PaymentPlanStatus.CANCELLED,"refinancing")
        );
    }

    @Test void activeNonDefaultedPlanCannotRequestExpirationAndRemainsUnchanged() throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        createPlanConfiguration(today);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String taxpayer=sessions.token(DemoRole.TAXPAYER,debt.taxpayerId);
        String rentas=sessions.token(DemoRole.RENTAS);
        String response=performAsSession(post("/api/v1/payment-plan-requests").header("X-Demo-Session",taxpayer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                    new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long requestId=json.readTree(response).get("id").asLong();
        performAsSession(post("/api/v1/payment-plan-requests/{id}/grant",requestId).header("X-Demo-Session",rentas)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ApiDtos.GrantPaymentPlanRequest(null))))
            .andExpect(status().isOk());
        PaymentPlan plan=plans.findById(planRequests.findById(requestId).orElseThrow().paymentPlanId).orElseThrow();
        assertThat(plan.status).isEqualTo(PaymentPlanStatus.ACTIVE);
        assertThat(installments.findByPaymentPlanIdOrderByNumber(plan.id)).allMatch(i->!i.dueDate.isBefore(today));
        long expirationsBefore=expirations.count();
        long outboxBefore=outbox.count();
        BigDecimal balanceBefore=plan.outstandingPlanAmount;

        performAsSession(post("/api/v1/payment-plans/{planId}/expiration-requests",plan.id)
                .header("X-Demo-Session",rentas).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.CreatePlanExpirationRequest("not defaulted"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_NOT_DEFAULTED"));

        PaymentPlan stored=plans.findById(plan.id).orElseThrow();
        assertThat(stored.status).isEqualTo(PaymentPlanStatus.ACTIVE);
        assertThat(stored.outstandingPlanAmount).isEqualByComparingTo(balanceBefore);
        assertThat(expirations.count()).isEqualTo(expirationsBefore);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
    }

    @Test void exemptionRequestCannotStartReviewTwiceAndKeepsReviewState() throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        ApiDtos.CreateExemptionRequest body=new ApiDtos.CreateExemptionRequest(debt.taxpayerId,debt.taxConceptId,
            "Transition matrix",new BigDecimal("10.00"),today,today.plusMonths(1));

        SecurityContextHolder.clearContext();
        String response=mvc.perform(post("/api/v1/exemption-requests")
                .header("X-Demo-Session",sessions.token(DemoRole.TAXPAYER,debt.taxpayerId))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long requestId=json.readTree(response).get("id").asLong();
        String rentas=sessions.token(DemoRole.RENTAS);

        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/exemption-requests/{id}/start-review",requestId).header("X-Demo-Session",rentas))
            .andExpect(status().isOk());

        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/exemption-requests/{id}/start-review",requestId).header("X-Demo-Session",rentas))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_EXEMPTION_TRANSITION"));

        assertThat(exemptionRequests.findById(requestId).orElseThrow().status).isEqualTo(ExemptionRequestStatus.UNDER_REVIEW);
    }

    @ParameterizedTest(name = "PENDING exemption rejects {0} with 409")
    @MethodSource("invalidPendingExemptionTransitions")
    void pendingExemptionRejectsLifecycleTransitionsAndRemainsPending(String action,DemoRole actor,Object body,String code) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        ApiDtos.CreateExemptionRequest create=new ApiDtos.CreateExemptionRequest(debt.taxpayerId,debt.taxConceptId,
            "Transition matrix",new BigDecimal("10.00"),today,today.plusMonths(1));

        SecurityContextHolder.clearContext();
        String response=mvc.perform(post("/api/v1/exemption-requests")
                .header("X-Demo-Session",sessions.token(DemoRole.TAXPAYER,debt.taxpayerId))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long requestId=json.readTree(response).get("id").asLong();
        long outboxBefore=outbox.count();

        SecurityContextHolder.clearContext();
        var call=post("/api/v1/exemption-requests/{id}/"+action,requestId)
            .header("X-Demo-Session",sessions.token(actor,actor==DemoRole.TAXPAYER?debt.taxpayerId:null));
        if(body!=null)call.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        mvc.perform(call).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));

        assertThat(exemptionRequests.findById(requestId).orElseThrow().status).isEqualTo(ExemptionRequestStatus.PENDING);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
    }

    private static Stream<Arguments> invalidPendingExemptionTransitions() {
        return Stream.of(
            Arguments.of("request-documentation",DemoRole.RENTAS,new ApiDtos.RequestDocumentationRequest("document required"),"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of("documentation",DemoRole.TAXPAYER,new ApiDtos.SubmitDocumentationRequest("doc-ref","IDENTITY","identity.pdf"),"DOCUMENTATION_NOT_REQUIRED"),
            Arguments.of("submit-resolution",DemoRole.RENTAS,null,"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of("approve",DemoRole.SUPERVISOR,new ApiDtos.ApproveExemptionRequest(null,null,null,null),"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of("reject",DemoRole.SUPERVISOR,new ApiDtos.RejectionRequest("not ready"),"INVALID_EXEMPTION_TRANSITION")
        );
    }

    @ParameterizedTest(name = "{0} exemption rejects {1} with 409")
    @MethodSource("invalidAdvancedExemptionTransitions")
    void advancedExemptionStatesRejectInvalidLifecycleActions(String state,String action,DemoRole actor,Object body,String code) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String taxpayer=sessions.token(DemoRole.TAXPAYER,debt.taxpayerId);
        String rentas=sessions.token(DemoRole.RENTAS);
        String supervisor=sessions.token(DemoRole.SUPERVISOR);
        String response=performAsSession(post("/api/v1/exemption-requests").header("X-Demo-Session",taxpayer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                    new ApiDtos.CreateExemptionRequest(debt.taxpayerId,debt.taxConceptId,"Transition matrix",
                        new BigDecimal("10.00"),today,today.plusMonths(1)))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long requestId=json.readTree(response).get("id").asLong();

        if(!"PENDING".equals(state)) {
            performAsSession(post("/api/v1/exemption-requests/{id}/start-review",requestId).header("X-Demo-Session",rentas))
                .andExpect(status().isOk());
        }
        if("DOCUMENTATION_REQUIRED".equals(state)) {
            performAsSession(post("/api/v1/exemption-requests/{id}/request-documentation",requestId)
                    .header("X-Demo-Session",rentas).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new ApiDtos.RequestDocumentationRequest("document required"))))
                .andExpect(status().isOk());
        } else if("PENDING_RESOLUTION".equals(state)||"APPROVED".equals(state)||"REJECTED".equals(state)) {
            performAsSession(post("/api/v1/exemption-requests/{id}/submit-resolution",requestId).header("X-Demo-Session",rentas))
                .andExpect(status().isOk());
            if("APPROVED".equals(state)) {
                performAsSession(post("/api/v1/exemption-requests/{id}/approve",requestId).header("X-Demo-Session",supervisor))
                    .andExpect(status().isOk());
            } else if("REJECTED".equals(state)) {
                performAsSession(post("/api/v1/exemption-requests/{id}/reject",requestId).header("X-Demo-Session",supervisor)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ApiDtos.RejectionRequest("rejected"))))
                    .andExpect(status().isOk());
            }
        }

        ExemptionRequestStatus expectedState=ExemptionRequestStatus.valueOf(state);
        long outboxBefore=outbox.count();
        long exemptionCountBefore=exemptions.count();
        long documentsBefore=exemptionDocuments.count();
        var call=post("/api/v1/exemption-requests/{id}/"+action,requestId)
            .header("X-Demo-Session",actor==DemoRole.RENTAS?rentas:actor==DemoRole.SUPERVISOR?supervisor:taxpayer);
        if(body!=null)call.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        performAsSession(call).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));

        assertThat(exemptionRequests.findById(requestId).orElseThrow().status).isEqualTo(expectedState);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
        assertThat(exemptions.count()).isEqualTo(exemptionCountBefore);
        assertThat(exemptionDocuments.count()).isEqualTo(documentsBefore);
    }

    private static Stream<Arguments> invalidAdvancedExemptionTransitions() {
        Stream<Arguments> underReview=Stream.of(
            Arguments.of("UNDER_REVIEW","documentation",DemoRole.TAXPAYER,
                new ApiDtos.SubmitDocumentationRequest("doc-ref","IDENTITY","identity.pdf"),"DOCUMENTATION_NOT_REQUIRED"),
            Arguments.of("UNDER_REVIEW","approve",DemoRole.SUPERVISOR,
                new ApiDtos.ApproveExemptionRequest(null,null,null,null),"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of("UNDER_REVIEW","reject",DemoRole.SUPERVISOR,
                new ApiDtos.RejectionRequest("not ready"),"INVALID_EXEMPTION_TRANSITION"));
        Stream<Arguments> documentationRequired=Stream.of(
            Arguments.of("DOCUMENTATION_REQUIRED","start-review",DemoRole.RENTAS,null,"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of("DOCUMENTATION_REQUIRED","request-documentation",DemoRole.RENTAS,
                new ApiDtos.RequestDocumentationRequest("again"),"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of("DOCUMENTATION_REQUIRED","submit-resolution",DemoRole.RENTAS,null,"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of("DOCUMENTATION_REQUIRED","approve",DemoRole.SUPERVISOR,
                new ApiDtos.ApproveExemptionRequest(null,null,null,null),"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of("DOCUMENTATION_REQUIRED","reject",DemoRole.SUPERVISOR,
                new ApiDtos.RejectionRequest("not ready"),"INVALID_EXEMPTION_TRANSITION"));
        Stream<Arguments> pendingResolution=Stream.of(
            Arguments.of("PENDING_RESOLUTION","start-review",DemoRole.RENTAS,null,"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of("PENDING_RESOLUTION","request-documentation",DemoRole.RENTAS,
                new ApiDtos.RequestDocumentationRequest("again"),"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of("PENDING_RESOLUTION","documentation",DemoRole.TAXPAYER,
                new ApiDtos.SubmitDocumentationRequest("doc-ref","IDENTITY","identity.pdf"),"DOCUMENTATION_NOT_REQUIRED"),
            Arguments.of("PENDING_RESOLUTION","submit-resolution",DemoRole.RENTAS,null,"INVALID_EXEMPTION_TRANSITION"));
        Stream<Arguments> approved=terminalExemptionTransitions("APPROVED");
        Stream<Arguments> rejected=terminalExemptionTransitions("REJECTED");
        return Stream.of(underReview,documentationRequired,pendingResolution,approved,rejected).flatMap(s->s);
    }

    private static Stream<Arguments> terminalExemptionTransitions(String state) {
        return Stream.of(
            Arguments.of(state,"start-review",DemoRole.RENTAS,null,"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of(state,"request-documentation",DemoRole.RENTAS,
                new ApiDtos.RequestDocumentationRequest("again"),"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of(state,"documentation",DemoRole.TAXPAYER,
                new ApiDtos.SubmitDocumentationRequest("doc-ref","IDENTITY","identity.pdf"),"DOCUMENTATION_NOT_REQUIRED"),
            Arguments.of(state,"submit-resolution",DemoRole.RENTAS,null,"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of(state,"approve",DemoRole.SUPERVISOR,
                new ApiDtos.ApproveExemptionRequest(null,null,null,null),"INVALID_EXEMPTION_TRANSITION"),
            Arguments.of(state,"reject",DemoRole.SUPERVISOR,
                new ApiDtos.RejectionRequest("again"),"INVALID_EXEMPTION_TRANSITION"));
    }

    @Test void pendingReversalCannotBeExecutedAndRemainsPending() throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        Payment payment=paymentService.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,
            new BigDecimal("10.00"),null));
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        SecurityContextHolder.clearContext();
        String response=performAsSession(post("/api/v1/payments/{id}/reversal-requests",payment.id)
                .header("X-Demo-Session",sessions.token(DemoRole.CASHIER))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ApiDtos.CreateReversalRequest("test"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long reversalId=json.readTree(response).get("id").asLong();

        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/payment-reversals/{id}/execute",reversalId)
                .header("X-Demo-Session",sessions.token(DemoRole.RENTAS)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REVERSAL_NOT_APPROVED"));

        assertThat(reversals.findById(reversalId).orElseThrow().status).isEqualTo(PaymentReversalStatus.PENDING_APPROVAL);
        assertThat(payments.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.CONFIRMED);
    }

    @ParameterizedTest(name = "REJECTED reversal rejects {0} with 409")
    @MethodSource("invalidRejectedReversalTransitions")
    void rejectedReversalRejectsFurtherTransitionsAndPreservesPayment(String action,DemoRole actor,Object body,String code) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        Payment payment=paymentService.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,
            new BigDecimal("10.00"),null));
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        SecurityContextHolder.clearContext();
        String response=performAsSession(post("/api/v1/payments/{id}/reversal-requests",payment.id)
                .header("X-Demo-Session",sessions.token(DemoRole.CASHIER))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ApiDtos.CreateReversalRequest("test"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long reversalId=json.readTree(response).get("id").asLong();

        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/payment-reversals/{id}/reject",reversalId)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ApiDtos.RejectionRequest("rejected"))))
            .andExpect(status().isOk());
        long outboxAfterReject=outbox.count();

        SecurityContextHolder.clearContext();
        var call=post("/api/v1/payment-reversals/{id}/"+action,reversalId)
            .header("X-Demo-Session",sessions.token(actor));
        if(body!=null)call.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        mvc.perform(call).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));

        assertThat(reversals.findById(reversalId).orElseThrow().status).isEqualTo(PaymentReversalStatus.REJECTED);
        assertThat(payments.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(outbox.count()).isEqualTo(outboxAfterReject);
    }

    private static Stream<Arguments> invalidRejectedReversalTransitions() {
        return Stream.of(
            Arguments.of("approve",DemoRole.SUPERVISOR,null,"REVERSAL_NOT_PENDING"),
            Arguments.of("reject",DemoRole.SUPERVISOR,new ApiDtos.RejectionRequest("again"),"REVERSAL_NOT_PENDING"),
            Arguments.of("execute",DemoRole.RENTAS,null,"REVERSAL_NOT_APPROVED")
        );
    }

    @ParameterizedTest(name = "{0} reversal rejects {1} with 409")
    @MethodSource("invalidOtherReversalTransitions")
    void approvedOrExecutedReversalRejectsInvalidLifecycleAction(PaymentReversalStatus state,String action,DemoRole actor,String code) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        Payment payment=paymentService.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,
            new BigDecimal("10.00"),null));
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String cashier=sessions.token(DemoRole.CASHIER);
        String supervisor=sessions.token(DemoRole.SUPERVISOR);
        String rentas=sessions.token(DemoRole.RENTAS);
        String response=performAsSession(post("/api/v1/payments/{id}/reversal-requests",payment.id)
                .header("X-Demo-Session",cashier).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.CreateReversalRequest("matrix"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long reversalId=json.readTree(response).get("id").asLong();
        performAsSession(post("/api/v1/payment-reversals/{id}/approve",reversalId).header("X-Demo-Session",supervisor))
            .andExpect(status().isOk());
        if(state==PaymentReversalStatus.EXECUTED) {
            performAsSession(post("/api/v1/payment-reversals/{id}/execute",reversalId).header("X-Demo-Session",rentas))
                .andExpect(status().isOk());
        }

        Payment storedPayment=payments.findById(payment.id).orElseThrow();
        long outboxBefore=outbox.count();
        long paymentsBefore=payments.count();
        long allocationsBefore=allocations.count();
        var call=post("/api/v1/payment-reversals/{id}/"+action,reversalId)
            .header("X-Demo-Session",actor==DemoRole.SUPERVISOR?supervisor:rentas);
        if("reject".equals(action))call.contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new ApiDtos.RejectionRequest("again")));
        performAsSession(call).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));

        assertThat(reversals.findById(reversalId).orElseThrow().status).isEqualTo(state);
        assertThat(payments.findById(payment.id).orElseThrow().status).isEqualTo(storedPayment.status);
        assertThat(payments.findById(payment.id).orElseThrow().unallocatedAmount).isEqualByComparingTo(storedPayment.unallocatedAmount);
        assertThat(payments.count()).isEqualTo(paymentsBefore);
        assertThat(allocations.count()).isEqualTo(allocationsBefore);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
    }

    private static Stream<Arguments> invalidOtherReversalTransitions() {
        return Stream.of(
            Arguments.of(PaymentReversalStatus.APPROVED,"approve",DemoRole.SUPERVISOR,"REVERSAL_NOT_PENDING"),
            Arguments.of(PaymentReversalStatus.APPROVED,"reject",DemoRole.SUPERVISOR,"REVERSAL_NOT_PENDING"),
            Arguments.of(PaymentReversalStatus.EXECUTED,"approve",DemoRole.SUPERVISOR,"REVERSAL_NOT_PENDING"),
            Arguments.of(PaymentReversalStatus.EXECUTED,"reject",DemoRole.SUPERVISOR,"REVERSAL_NOT_PENDING"),
            Arguments.of(PaymentReversalStatus.EXECUTED,"execute",DemoRole.RENTAS,"REVERSAL_NOT_APPROVED")
        );
    }

    @Test void liquidationRunSemanticDateErrorIs422ButInvalidApprovalStateIs409() throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String rentas=sessions.token(DemoRole.RENTAS);
        ApiDtos.LiquidationRunItemRequest item=new ApiDtos.LiquidationRunItemRequest(debt.taxpayerId,BigDecimal.ZERO);
        ApiDtos.CreateLiquidationRunRequest invalidDueDate=new ApiDtos.CreateLiquidationRunRequest(
            debt.taxConceptId,YearMonth.from(today).toString(),today.minusDays(1),List.of(item));

        SecurityContextHolder.clearContext();
        long runCountBefore=liquidationRuns.count();
        mvc.perform(post("/api/v1/liquidation-runs").header("X-Demo-Session",rentas)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalidDueDate)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_DUE_DATE"));
        assertThat(liquidationRuns.count()).isEqualTo(runCountBefore);

        ApiDtos.CreateLiquidationRunRequest valid=new ApiDtos.CreateLiquidationRunRequest(
            debt.taxConceptId,YearMonth.from(today).toString(),today.plusDays(30),List.of(item));
        SecurityContextHolder.clearContext();
        String response=performAsSession(post("/api/v1/liquidation-runs").header("X-Demo-Session",rentas)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long runId=json.readTree(response).get("id").asLong();

        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/liquidation-runs/{id}/approve",runId)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RUN_NOT_PENDING"));

        assertThat(liquidationRuns.count()).isEqualTo(runCountBefore+1);
        assertThat(liquidationRuns.findById(runId).orElseThrow().status).isEqualTo(LiquidationRunStatus.DRAFT);
    }

    @ParameterizedTest(name = "REJECTED liquidation run rejects {0} with 409")
    @MethodSource("invalidRejectedLiquidationRunTransitions")
    void rejectedLiquidationRunRejectsFurtherTransitionsAndPreservesState(String action,DemoRole actor,Object body,String code) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        String suffix=UUID.randomUUID().toString();
        TaxpayerReference runTaxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
            TaxpayerType.CITIZEN,"RUN-MATRIX-"+suffix,suffix.replace("-",""),null,"Liquidation transition test"));
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String rentas=sessions.token(DemoRole.RENTAS);
        ApiDtos.CreateLiquidationRunRequest create=new ApiDtos.CreateLiquidationRunRequest(debt.taxConceptId,
            YearMonth.from(today).toString(),today.plusDays(30),List.of(new ApiDtos.LiquidationRunItemRequest(runTaxpayer.id,BigDecimal.ZERO)));

        SecurityContextHolder.clearContext();
        String response=performAsSession(post("/api/v1/liquidation-runs").header("X-Demo-Session",rentas)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long runId=json.readTree(response).get("id").asLong();
        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/liquidation-runs/{id}/preview",runId).header("X-Demo-Session",rentas))
            .andExpect(status().isOk());
        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/liquidation-runs/{id}/submit",runId).header("X-Demo-Session",rentas))
            .andExpect(status().isOk());
        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/liquidation-runs/{id}/reject",runId)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ApiDtos.RejectionRequest("rejected"))))
            .andExpect(status().isOk());
        long outboxAfterReject=outbox.count();
        long liquidationsAfterReject=liquidationRepository.count();

        SecurityContextHolder.clearContext();
        var call=post("/api/v1/liquidation-runs/{id}/"+action,runId)
            .header("X-Demo-Session",sessions.token(actor));
        if(body!=null)call.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        mvc.perform(call).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));

        assertThat(liquidationRuns.findById(runId).orElseThrow().status).isEqualTo(LiquidationRunStatus.REJECTED);
        assertThat(outbox.count()).isEqualTo(outboxAfterReject);
        assertThat(liquidationRepository.count()).isEqualTo(liquidationsAfterReject);
    }

    private static Stream<Arguments> invalidRejectedLiquidationRunTransitions() {
        return Stream.of(
            Arguments.of("preview",DemoRole.RENTAS,null,"RUN_NOT_DRAFT"),
            Arguments.of("submit",DemoRole.RENTAS,null,"RUN_NOT_READY"),
            Arguments.of("approve",DemoRole.SUPERVISOR,null,"RUN_NOT_PENDING"),
            Arguments.of("reject",DemoRole.SUPERVISOR,new ApiDtos.RejectionRequest("again"),"RUN_NOT_PENDING"),
            Arguments.of("execute",DemoRole.RENTAS,null,"RUN_NOT_APPROVED")
        );
    }

    @ParameterizedTest(name = "{0} liquidation run rejects {1} with 409")
    @MethodSource("invalidOtherLiquidationRunTransitions")
    void otherLiquidationRunStatesRejectInvalidLifecycleAction(String state,String action,DemoRole actor,String code) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        String suffix=UUID.randomUUID().toString();
        TaxpayerReference runTaxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
            TaxpayerType.CITIZEN,"RUN-STATE-MATRIX-"+suffix,suffix.replace("-",""),null,"Liquidation state test"));
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String rentas=sessions.token(DemoRole.RENTAS);
        String supervisor=sessions.token(DemoRole.SUPERVISOR);
        ApiDtos.CreateLiquidationRunRequest create=new ApiDtos.CreateLiquidationRunRequest(debt.taxConceptId,
            YearMonth.from(today).toString(),today.plusDays(30),List.of(new ApiDtos.LiquidationRunItemRequest(runTaxpayer.id,BigDecimal.ZERO)));
        String response=performAsSession(post("/api/v1/liquidation-runs").header("X-Demo-Session",rentas)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long runId=json.readTree(response).get("id").asLong();

        if(!"DRAFT".equals(state)) {
            performAsSession(post("/api/v1/liquidation-runs/{id}/preview",runId).header("X-Demo-Session",rentas))
                .andExpect(status().isOk());
            performAsSession(post("/api/v1/liquidation-runs/{id}/submit",runId).header("X-Demo-Session",rentas))
                .andExpect(status().isOk());
        }
        if("APPROVED".equals(state)||"EXECUTED".equals(state)) {
            performAsSession(post("/api/v1/liquidation-runs/{id}/approve",runId).header("X-Demo-Session",supervisor))
                .andExpect(status().isOk());
        }
        if("EXECUTED".equals(state)) {
            performAsSession(post("/api/v1/liquidation-runs/{id}/execute",runId).header("X-Demo-Session",rentas))
                .andExpect(status().isOk());
        }

        LiquidationRun before=liquidationRuns.findById(runId).orElseThrow();
        long outboxBefore=outbox.count();
        long liquidationCountBefore=liquidationRepository.count();
        List<LiquidationRunItem> itemsBefore=liquidationRunItems.findByLiquidationRunIdOrderById(runId);
        long itemCountBefore=itemsBefore.size();
        List<String> itemSnapshot=itemsBefore.stream().map(this::liquidationItemSnapshot).toList();
        var call=post("/api/v1/liquidation-runs/{id}/"+action,runId)
            .header("X-Demo-Session",actor==DemoRole.SUPERVISOR?supervisor:rentas);
        if("reject".equals(action))call.contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new ApiDtos.RejectionRequest("again")));
        performAsSession(call).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));

        LiquidationRun stored=liquidationRuns.findById(runId).orElseThrow();
        assertThat(stored.status.name()).isEqualTo(state);
        assertThat(stored.validItems).isEqualTo(before.validItems);
        assertThat(stored.errorItems).isEqualTo(before.errorItems);
        assertThat(stored.estimatedTotalAmount).isEqualByComparingTo(before.estimatedTotalAmount);
        List<LiquidationRunItem> itemsAfter=liquidationRunItems.findByLiquidationRunIdOrderById(runId);
        assertThat(itemsAfter).hasSize((int)itemCountBefore);
        assertThat(itemsAfter.stream().map(this::liquidationItemSnapshot).toList()).containsExactlyElementsOf(itemSnapshot);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
        assertThat(liquidationRepository.count()).isEqualTo(liquidationCountBefore);
    }

    private String liquidationItemSnapshot(LiquidationRunItem item) {
        return item.id+"|"+item.status+"|"+item.previewAmount+"|"+item.errorCode+"|"+item.errorMessage+"|"+item.liquidationId;
    }

    private static Stream<Arguments> invalidOtherLiquidationRunTransitions() {
        return Stream.of(
            Arguments.of("DRAFT","reject",DemoRole.SUPERVISOR,"RUN_NOT_PENDING"),
            Arguments.of("DRAFT","approve",DemoRole.SUPERVISOR,"RUN_NOT_PENDING"),
            Arguments.of("DRAFT","execute",DemoRole.RENTAS,"RUN_NOT_APPROVED"),
            Arguments.of("PENDING_APPROVAL","preview",DemoRole.RENTAS,"RUN_NOT_DRAFT"),
            Arguments.of("PENDING_APPROVAL","submit",DemoRole.RENTAS,"RUN_NOT_READY"),
            Arguments.of("PENDING_APPROVAL","execute",DemoRole.RENTAS,"RUN_NOT_APPROVED"),
            Arguments.of("APPROVED","preview",DemoRole.RENTAS,"RUN_NOT_DRAFT"),
            Arguments.of("APPROVED","submit",DemoRole.RENTAS,"RUN_NOT_READY"),
            Arguments.of("APPROVED","approve",DemoRole.SUPERVISOR,"RUN_NOT_PENDING"),
            Arguments.of("APPROVED","reject",DemoRole.SUPERVISOR,"RUN_NOT_PENDING"),
            Arguments.of("EXECUTED","preview",DemoRole.RENTAS,"RUN_NOT_DRAFT"),
            Arguments.of("EXECUTED","submit",DemoRole.RENTAS,"RUN_NOT_READY"),
            Arguments.of("EXECUTED","approve",DemoRole.SUPERVISOR,"RUN_NOT_PENDING"),
            Arguments.of("EXECUTED","reject",DemoRole.SUPERVISOR,"RUN_NOT_PENDING"),
            Arguments.of("EXECUTED","execute",DemoRole.RENTAS,"RUN_NOT_APPROVED")
        );
    }

    @ParameterizedTest(name = "{0} tax configuration rejects {1} with 409 and no mutation")
    @MethodSource("invalidTaxConfigurationTransitions")
    void invalidTaxConfigurationTransitionReturnsConflictWithoutMutation(
            TaxConfigurationStatus state,String action,String code) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        TaxConfiguration configuration=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
            debt.taxConceptId,CalculationType.FIXED,null,new BigDecimal("100.00"),null,null,true,true,today.minusDays(1),null));
        Long configurationId=configuration.id;
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        if(state!=TaxConfigurationStatus.DRAFT) {
            catalog.submit(configurationId);
            if(state==TaxConfigurationStatus.ACTIVE||state==TaxConfigurationStatus.INACTIVE) {
                TestActorSupport.asApprover(()->catalog.approve(configurationId));
                if(state==TaxConfigurationStatus.INACTIVE)catalog.deactivate(configurationId,"state-matrix fixture");
            } else if(state==TaxConfigurationStatus.REJECTED) {
                TestActorSupport.asApprover(()->catalog.reject(configurationId));
            }
        }
        configuration=taxConfigurations.findById(configurationId).orElseThrow();
        assertThat(configuration.status).isEqualTo(state);
        String before=json.writeValueAsString(ApiResponses.of(configuration));
        long configurationsBefore=taxConfigurations.count();
        long outboxBefore=outbox.count();
        List<Long> auditBefore=audit.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc(
            "TaxConfiguration",configurationId.toString()).stream().map(entry->entry.id).toList();
        var call=switch(action) {
            case "submit" -> post("/api/v1/tax-configurations/{id}/submit",configuration.id)
                .header("X-Demo-Session",sessions.token(DemoRole.RENTAS));
            case "approve" -> post("/api/v1/tax-configurations/{id}/approve",configuration.id)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR));
            case "reject" -> post("/api/v1/tax-configurations/{id}/reject",configuration.id)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.RejectionRequest("invalid transition")));
            case "deactivate" -> post("/api/v1/tax-configurations/{id}/deactivate",configuration.id)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.DeactivationRequest("invalid transition")));
            case "update" -> patch("/api/v1/tax-configurations/{id}",configuration.id)
                .header("X-Demo-Session",sessions.token(DemoRole.RENTAS))
                .header("If-Match",EntityTags.taxConfiguration(configuration)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.UpdateTaxConfigurationRequest(null,null,
                    new BigDecimal("101.00"),null,null,null,null,null,null)));
            default -> throw new IllegalArgumentException(action);
        };
        SecurityContextHolder.clearContext();
        mvc.perform(call).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));

        TaxConfiguration stored=taxConfigurations.findById(configuration.id).orElseThrow();
        assertThat(json.writeValueAsString(ApiResponses.of(stored))).isEqualTo(before);
        assertThat(taxConfigurations.count()).isEqualTo(configurationsBefore);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
        assertThat(audit.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc(
            "TaxConfiguration",configurationId.toString()).stream().map(entry->entry.id).toList())
            .containsExactlyElementsOf(auditBefore);
    }

    private static Stream<Arguments> invalidTaxConfigurationTransitions() {
        return Stream.of(
            Arguments.of(TaxConfigurationStatus.DRAFT,"approve","INVALID_CONFIGURATION_TRANSITION"),
            Arguments.of(TaxConfigurationStatus.DRAFT,"reject","INVALID_CONFIGURATION_TRANSITION"),
            Arguments.of(TaxConfigurationStatus.DRAFT,"deactivate","CONFIGURATION_NOT_ACTIVE"),
            Arguments.of(TaxConfigurationStatus.PENDING_APPROVAL,"submit","INVALID_CONFIGURATION_TRANSITION"),
            Arguments.of(TaxConfigurationStatus.PENDING_APPROVAL,"update","CONFIGURATION_NOT_EDITABLE"),
            Arguments.of(TaxConfigurationStatus.PENDING_APPROVAL,"deactivate","CONFIGURATION_NOT_ACTIVE"),
            Arguments.of(TaxConfigurationStatus.ACTIVE,"submit","INVALID_CONFIGURATION_TRANSITION"),
            Arguments.of(TaxConfigurationStatus.ACTIVE,"update","CONFIGURATION_NOT_EDITABLE"),
            Arguments.of(TaxConfigurationStatus.ACTIVE,"approve","INVALID_CONFIGURATION_TRANSITION"),
            Arguments.of(TaxConfigurationStatus.ACTIVE,"reject","INVALID_CONFIGURATION_TRANSITION"),
            Arguments.of(TaxConfigurationStatus.REJECTED,"submit","INVALID_CONFIGURATION_TRANSITION"),
            Arguments.of(TaxConfigurationStatus.REJECTED,"update","CONFIGURATION_NOT_EDITABLE"),
            Arguments.of(TaxConfigurationStatus.REJECTED,"approve","INVALID_CONFIGURATION_TRANSITION"),
            Arguments.of(TaxConfigurationStatus.REJECTED,"reject","INVALID_CONFIGURATION_TRANSITION"),
            Arguments.of(TaxConfigurationStatus.REJECTED,"deactivate","CONFIGURATION_NOT_ACTIVE"),
            Arguments.of(TaxConfigurationStatus.INACTIVE,"submit","INVALID_CONFIGURATION_TRANSITION"),
            Arguments.of(TaxConfigurationStatus.INACTIVE,"update","CONFIGURATION_NOT_EDITABLE"),
            Arguments.of(TaxConfigurationStatus.INACTIVE,"approve","INVALID_CONFIGURATION_TRANSITION"),
            Arguments.of(TaxConfigurationStatus.INACTIVE,"reject","INVALID_CONFIGURATION_TRANSITION"),
            Arguments.of(TaxConfigurationStatus.INACTIVE,"deactivate","CONFIGURATION_NOT_ACTIVE")
        );
    }

    @ParameterizedTest(name = "{0} adjustment rejects {1} with 409 and no mutation")
    @MethodSource("invalidResolvedAdjustmentTransitions")
    void resolvedAdjustmentRejectsSecondResolutionWithoutMutation(AdjustmentStatus state,String action) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String rentas=sessions.token(DemoRole.RENTAS);
        String supervisor=sessions.token(DemoRole.SUPERVISOR);
        String response=performAsSession(post("/api/v1/adjustments").header("X-Demo-Session",rentas)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                    new ApiDtos.CreateAdjustmentRequest(debt.id,AdjustmentType.DISCOUNT,new BigDecimal("1.00"),"transition matrix"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long adjustmentId=json.readTree(response).get("id").asLong();
        if(state==AdjustmentStatus.APPROVED) {
            performAsSession(post("/api/v1/adjustments/{id}/approve",adjustmentId).header("X-Demo-Session",supervisor))
                .andExpect(status().isOk());
        } else {
            performAsSession(post("/api/v1/adjustments/{id}/reject",adjustmentId).header("X-Demo-Session",supervisor)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new ApiDtos.RejectionRequest("rejected"))))
                .andExpect(status().isOk());
        }
        AdjustmentRequest before=adjustments.findById(adjustmentId).orElseThrow();
        String adjustmentSnapshot=json.writeValueAsString(ApiResponses.of(before));
        Debt debtBefore=debts.findById(debt.id).orElseThrow();
        String debtSnapshot=debtBefore.status+"|"+debtBefore.currentAmount+"|"+debtBefore.outstandingBalance;
        long adjustmentCountBefore=adjustments.count();
        long outboxBefore=outbox.count();
        long auditBefore=audit.count();
        var call=post("/api/v1/adjustments/{id}/"+action,adjustmentId).header("X-Demo-Session",supervisor);
        if("reject".equals(action))call.contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new ApiDtos.RejectionRequest("again")));
        performAsSession(call).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADJUSTMENT_NOT_PENDING"));

        assertThat(json.writeValueAsString(ApiResponses.of(adjustments.findById(adjustmentId).orElseThrow())))
            .isEqualTo(adjustmentSnapshot);
        Debt storedDebt=debts.findById(debt.id).orElseThrow();
        assertThat(storedDebt.status+"|"+storedDebt.currentAmount+"|"+storedDebt.outstandingBalance).isEqualTo(debtSnapshot);
        assertThat(adjustments.count()).isEqualTo(adjustmentCountBefore);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
        assertThat(audit.count()).isEqualTo(auditBefore);
    }

    private static Stream<Arguments> invalidResolvedAdjustmentTransitions() {
        return Stream.of(
            Arguments.of(AdjustmentStatus.APPROVED,"approve"),
            Arguments.of(AdjustmentStatus.APPROVED,"reject"),
            Arguments.of(AdjustmentStatus.REJECTED,"approve"),
            Arguments.of(AdjustmentStatus.REJECTED,"reject")
        );
    }

    @ParameterizedTest(name = "{0} plan expiration rejects {1} with 409 and no mutation")
    @MethodSource("invalidResolvedExpirationTransitions")
    void resolvedPlanExpirationRejectsSecondResolutionWithoutMutation(PlanExpirationStatus state,String action) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        createPlanConfiguration(today);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String taxpayer=sessions.token(DemoRole.TAXPAYER,debt.taxpayerId);
        String rentas=sessions.token(DemoRole.RENTAS);
        String supervisor=sessions.token(DemoRole.SUPERVISOR);
        String planRequestResponse=performAsSession(post("/api/v1/payment-plan-requests").header("X-Demo-Session",taxpayer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                    new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long planRequestId=json.readTree(planRequestResponse).get("id").asLong();
        performAsSession(post("/api/v1/payment-plan-requests/{id}/grant",planRequestId).header("X-Demo-Session",rentas)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ApiDtos.GrantPaymentPlanRequest(null))))
            .andExpect(status().isOk());
        PaymentPlan plan=plans.findById(planRequests.findById(planRequestId).orElseThrow().paymentPlanId).orElseThrow();
        List<Installment> overdueInstallments=installments.findByPaymentPlanIdOrderByNumber(plan.id);
        overdueInstallments.forEach(installment->{installment.dueDate=today.minusDays(1);installment.status=InstallmentStatus.OVERDUE;});
        installments.saveAllAndFlush(overdueInstallments);
        String expirationResponse=performAsSession(post("/api/v1/payment-plans/{planId}/expiration-requests",plan.id)
                .header("X-Demo-Session",rentas).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.CreatePlanExpirationRequest("defaulted"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long expirationId=json.readTree(expirationResponse).get("id").asLong();
        if(state==PlanExpirationStatus.APPROVED) {
            performAsSession(post("/api/v1/payment-plan-expirations/{id}/approve",expirationId)
                    .header("X-Demo-Session",supervisor)).andExpect(status().isOk());
        } else {
            performAsSession(post("/api/v1/payment-plan-expirations/{id}/reject",expirationId)
                    .header("X-Demo-Session",supervisor).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new ApiDtos.RejectionRequest("rejected"))))
                .andExpect(status().isOk());
        }

        PlanExpirationRequest before=expirations.findById(expirationId).orElseThrow();
        String expirationSnapshot=json.writeValueAsString(ApiResponses.of(before));
        PaymentPlan planBefore=plans.findById(plan.id).orElseThrow();
        String planSnapshot=planBefore.status+"|"+planBefore.outstandingPlanAmount;
        List<String> installmentSnapshots=installments.findByPaymentPlanIdOrderByNumber(plan.id).stream()
            .map(i->i.number+"|"+i.status+"|"+i.paidAmount+"|"+i.outstandingAmount).toList();
        long expirationCountBefore=expirations.count();
        long outboxBefore=outbox.count();
        long auditBefore=audit.count();
        var call=post("/api/v1/payment-plan-expirations/{id}/"+action,expirationId)
            .header("X-Demo-Session",supervisor);
        if("reject".equals(action))call.contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new ApiDtos.RejectionRequest("again")));
        performAsSession(call).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXPIRATION_NOT_PENDING"));

        assertThat(json.writeValueAsString(ApiResponses.of(expirations.findById(expirationId).orElseThrow())))
            .isEqualTo(expirationSnapshot);
        PaymentPlan storedPlan=plans.findById(plan.id).orElseThrow();
        assertThat(storedPlan.status+"|"+storedPlan.outstandingPlanAmount).isEqualTo(planSnapshot);
        assertThat(installments.findByPaymentPlanIdOrderByNumber(plan.id).stream()
            .map(i->i.number+"|"+i.status+"|"+i.paidAmount+"|"+i.outstandingAmount).toList())
            .containsExactlyElementsOf(installmentSnapshots);
        assertThat(expirations.count()).isEqualTo(expirationCountBefore);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
        assertThat(audit.count()).isEqualTo(auditBefore);
    }

    private static Stream<Arguments> invalidResolvedExpirationTransitions() {
        return Stream.of(
            Arguments.of(PlanExpirationStatus.APPROVED,"approve"),
            Arguments.of(PlanExpirationStatus.APPROVED,"reject"),
            Arguments.of(PlanExpirationStatus.REJECTED,"approve"),
            Arguments.of(PlanExpirationStatus.REJECTED,"reject")
        );
    }

    @ParameterizedTest(name = "{0} refinancing request rejects {1} with 409 and no mutation")
    @MethodSource("invalidRefinancingRequestTransitions")
    void invalidRefinancingRequestTransitionReturnsConflictWithoutMutation(
            RefinancingRequestStatus state,String action,String code) throws Exception {
        LocalDate today=dates.today();
        Debt debt=createDebt(today);
        createPlanConfiguration(today);
        DemoAuthSessions sessions=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuth);
        String taxpayer=sessions.token(DemoRole.TAXPAYER,debt.taxpayerId);
        String rentas=sessions.token(DemoRole.RENTAS);
        String supervisor=sessions.token(DemoRole.SUPERVISOR);
        String planRequestResponse=performAsSession(post("/api/v1/payment-plan-requests").header("X-Demo-Session",taxpayer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                    new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long planRequestId=json.readTree(planRequestResponse).get("id").asLong();
        performAsSession(post("/api/v1/payment-plan-requests/{id}/grant",planRequestId).header("X-Demo-Session",rentas)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ApiDtos.GrantPaymentPlanRequest(null))))
            .andExpect(status().isOk());
        PaymentPlan plan=plans.findById(planRequests.findById(planRequestId).orElseThrow().paymentPlanId).orElseThrow();
        int requestedInstallments=state==RefinancingRequestStatus.PENDING_EXCEPTION_APPROVAL?1:3;
        String refinancingResponse=performAsSession(post("/api/v1/payment-plans/{planId}/refinancing-requests",plan.id)
                .header("X-Demo-Session",taxpayer).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.CreateRefinancingRequest(requestedInstallments))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long refinancingId=json.readTree(refinancingResponse).get("id").asLong();
        if(state==RefinancingRequestStatus.PENDING_EXCEPTION_APPROVAL) {
            performAsSession(post("/api/v1/refinancing-requests/{id}/submit-exception",refinancingId)
                    .header("X-Demo-Session",rentas).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new ApiDtos.SubmitPlanExceptionRequest("exceptional"))))
                .andExpect(status().isOk());
        } else if(state==RefinancingRequestStatus.GRANTED) {
            performAsSession(post("/api/v1/refinancing-requests/{id}/grant",refinancingId).header("X-Demo-Session",rentas))
                .andExpect(status().isOk());
        } else if(state==RefinancingRequestStatus.REJECTED) {
            performAsSession(post("/api/v1/refinancing-requests/{id}/reject",refinancingId)
                    .header("X-Demo-Session",rentas).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new ApiDtos.RejectionRequest("rejected"))))
                .andExpect(status().isOk());
        }

        RefinancingRequest before=refinancings.findById(refinancingId).orElseThrow();
        assertThat(before.status).isEqualTo(state);
        String requestSnapshot=json.writeValueAsString(ApiResponses.of(before));
        PaymentPlan planBefore=plans.findById(plan.id).orElseThrow();
        String planSnapshot=planBefore.status+"|"+planBefore.outstandingPlanAmount;
        long refinancingCountBefore=refinancings.count();
        long plansBefore=plans.count();
        long outboxBefore=outbox.count();
        long auditBefore=audit.count();
        var call=switch(action) {
            case "grant" -> post("/api/v1/refinancing-requests/{id}/grant",refinancingId)
                .header("X-Demo-Session",rentas);
            case "reject" -> post("/api/v1/refinancing-requests/{id}/reject",refinancingId)
                .header("X-Demo-Session",rentas).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.RejectionRequest("again")));
            case "submit-exception" -> post("/api/v1/refinancing-requests/{id}/submit-exception",refinancingId)
                .header("X-Demo-Session",rentas).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.SubmitPlanExceptionRequest("again")));
            case "approve-exception" -> post("/api/v1/refinancing-requests/{id}/approve-exception",refinancingId)
                .header("X-Demo-Session",supervisor);
            case "reject-exception" -> post("/api/v1/refinancing-requests/{id}/reject-exception",refinancingId)
                .header("X-Demo-Session",supervisor).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ApiDtos.RejectionRequest("again")));
            default -> throw new IllegalArgumentException(action);
        };
        performAsSession(call).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));

        assertThat(json.writeValueAsString(ApiResponses.of(refinancings.findById(refinancingId).orElseThrow())))
            .isEqualTo(requestSnapshot);
        PaymentPlan storedPlan=plans.findById(plan.id).orElseThrow();
        assertThat(storedPlan.status+"|"+storedPlan.outstandingPlanAmount).isEqualTo(planSnapshot);
        assertThat(refinancings.count()).isEqualTo(refinancingCountBefore);
        assertThat(plans.count()).isEqualTo(plansBefore);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
        assertThat(audit.count()).isEqualTo(auditBefore);
    }

    private static Stream<Arguments> invalidRefinancingRequestTransitions() {
        return Stream.of(
            Arguments.of(RefinancingRequestStatus.PENDING,"approve-exception","INVALID_REFINANCING_TRANSITION"),
            Arguments.of(RefinancingRequestStatus.PENDING_EXCEPTION_APPROVAL,"grant","INVALID_REFINANCING_TRANSITION"),
            Arguments.of(RefinancingRequestStatus.PENDING_EXCEPTION_APPROVAL,"submit-exception","INVALID_REFINANCING_TRANSITION"),
            Arguments.of(RefinancingRequestStatus.GRANTED,"grant","INVALID_REFINANCING_TRANSITION"),
            Arguments.of(RefinancingRequestStatus.GRANTED,"reject","REFINANCING_NOT_PENDING"),
            Arguments.of(RefinancingRequestStatus.GRANTED,"submit-exception","INVALID_REFINANCING_TRANSITION"),
            Arguments.of(RefinancingRequestStatus.GRANTED,"approve-exception","INVALID_REFINANCING_TRANSITION"),
            Arguments.of(RefinancingRequestStatus.GRANTED,"reject-exception","REFINANCING_NOT_PENDING"),
            Arguments.of(RefinancingRequestStatus.REJECTED,"grant","INVALID_REFINANCING_TRANSITION"),
            Arguments.of(RefinancingRequestStatus.REJECTED,"reject","REFINANCING_NOT_PENDING"),
            Arguments.of(RefinancingRequestStatus.REJECTED,"submit-exception","INVALID_REFINANCING_TRANSITION"),
            Arguments.of(RefinancingRequestStatus.REJECTED,"approve-exception","INVALID_REFINANCING_TRANSITION"),
            Arguments.of(RefinancingRequestStatus.REJECTED,"reject-exception","REFINANCING_NOT_PENDING")
        );
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

    private org.springframework.test.web.servlet.ResultActions performAsSession(
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        SecurityContextHolder.clearContext();
        return mvc.perform(request);
    }

    private void performAsSessionAndAssertRollbackOnly(
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,String errorCode) throws Exception {
        // MockMvc is synchronous, so the service joins the test transaction. Verify the
        // rejected request marks that transaction rollback-only instead of counting its
        // transient, uncommitted Payment row as a persisted effect.
        TransactionTemplate transaction=new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        AtomicBoolean rollbackOnly=new AtomicBoolean();
        try {
            transaction.execute(txStatus->{
                if(txStatus.isRollbackOnly())throw new IllegalStateException("Test transaction was already rollback-only before request");
                try {
                    performAsSession(request).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value(errorCode));
                } catch(Exception failure) {
                    throw new IllegalStateException("Installment rejection request failed",failure);
                }
                rollbackOnly.set(txStatus.isRollbackOnly());
                return null;
            });
        } catch(UnexpectedRollbackException expected) {
            if(!rollbackOnly.get())throw expected;
        }
        assertThat(rollbackOnly.get()).as("invalid installment payment marks its transaction rollback-only").isTrue();
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

    @Test void invalidDebtTransitionCasesCoverEveryUnsupportedStateOperationPair() {
        Set<DebtStateOperation> allOperations=Set.of(DebtStateOperation.values());
        Map<DebtStatus,Set<DebtStateOperation>> forbidden=Map.of(
            DebtStatus.PENDING,Set.of(),
            DebtStatus.PARTIALLY_PAID,Set.of(),
            DebtStatus.PAID,allOperations,
            DebtStatus.CANCELLED,allOperations);
        List<String> expected=Stream.of(DebtStatus.values())
            .flatMap(state->forbidden.get(state).stream()
                .map(operation->transitionKey(state,operation.name,operation.code)))
            .toList();
        List<String> policy=Stream.of(DebtStatus.values())
            .flatMap(state->Stream.of(DebtStateOperation.values())
                .filter(operation->!operation.allowed(state))
                .map(operation->transitionKey(state,operation.name,operation.code)))
            .toList();
        List<String> enumerated=invalidTransitions()
            .map(arguments->transitionKey((DebtStatus)arguments.get()[0],(String)arguments.get()[1],(String)arguments.get()[2]))
            .toList();

        assertThat(policy).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(enumerated).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
    }

    private static String transitionKey(DebtStatus state,String operation,String code) {
        return state+"/"+operation+"/"+code;
    }

    private enum DebtStateOperation {
        PAYMENT_ALLOCATION("payment-allocation","DEBT_NOT_PAYABLE",false),
        ADJUSTMENT("adjustment","DEBT_NOT_ADJUSTABLE",true),
        LATE_CHARGE("late-charge","DEBT_NOT_CHARGEABLE",false),
        CREDIT_APPLICATION("credit-application","DEBT_NOT_PAYABLE",false),
        BILL("bill","DEBT_NOT_BILLABLE",false),
        PLAN_REQUEST("plan-request","DEBT_NOT_ELIGIBLE_FOR_PLAN",false);

        private final String name;
        private final String code;
        private final boolean adjustment;

        DebtStateOperation(String name,String code,boolean adjustment) {
            this.name=name;
            this.code=code;
            this.adjustment=adjustment;
        }

        private boolean allowed(DebtStatus status) {
            DebtLifecycleState state=DebtLifecycleState.from(status);
            return adjustment?state.admiteAjuste():state.admiteCobro(BigDecimal.ONE);
        }
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
