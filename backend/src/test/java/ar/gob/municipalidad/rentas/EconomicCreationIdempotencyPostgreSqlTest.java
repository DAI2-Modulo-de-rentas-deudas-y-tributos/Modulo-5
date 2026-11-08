package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"rentas.due-date-processing-delay-ms=3600000","rentas.due-date-processing-initial-delay-ms=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker = true)
class EconomicCreationIdempotencyPostgreSqlTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/migration-postgresql,classpath:db/migration-demo");
    }

    @Autowired MockMvc mvc;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidationService;
    @Autowired LiquidationRunService liquidationRunService;
    @Autowired ExemptionService exemptionService;
    @Autowired ReversalService reversalService;
    @Autowired LateChargeRuleRepository lateChargeRules;
    @Autowired LateChargeApplicationRepository lateChargeApplications;
    @Autowired PaymentAllocationRepository allocations;
    @Autowired LiquidationRunRepository liquidationRuns;
    @Autowired LiquidationRunItemRepository liquidationRunItems;
    @Autowired FiscalProcessingDatePolicy dates;
    @Autowired PlanWorkflowService planWorkflow;
    @Autowired PaymentService paymentService;
    @Autowired PaymentRepository payments;
    @Autowired BillRepository bills;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired LiquidationRepository liquidations;
    @Autowired DebtRepository debts;
    @Autowired ExemptionRequestRepository exemptions;
    @Autowired PaymentReversalRepository reversals;
    @Autowired RefinancingRequestRepository refinancings;
    @Autowired OutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DueDateProcessingRepository dueDateRuns;
    @Autowired DueDateService dueDates;
    @Autowired DueDateProcessingWorker dueDateWorker;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void individualLiquidationConcurrentReplayReturnsSameCreatedResponseAndOneDebt() throws Exception {
        Fixture fixture = fixture("LIQ");
        String actor = "liquidation-idempotency-" + UUID.randomUUID();
        var auth = identity(actor, fixture.taxpayer.id, "RENTAS");
        String key = "liquidation-" + UUID.randomUUID();
        var request = new ApiDtos.LiquidationRequest(fixture.taxpayer.id, fixture.concept.id,
            YearMonth.now().plusMonths(1).toString(), BigDecimal.ZERO, LocalDate.now().plusDays(30));
        String body = json.writeValueAsString(request);

        List<MvcResult> responses = runTogether(
            () -> httpPost("/api/v1/liquidations", auth, key, body),
            () -> httpPost("/api/v1/liquidations", auth, key, body));

        String first = responses.get(0).getResponse().getContentAsString();
        String replay = responses.get(1).getResponse().getContentAsString();
        assertThat(responses).allSatisfy(response -> assertThat(response.getResponse().getStatus()).isEqualTo(201));
        assertThat(replay).isEqualTo(first);
        long liquidationId = json.readTree(first).path("id").asLong();
        assertThat(liquidations.findByTaxpayerId(fixture.taxpayer.id).stream()
            .filter(item -> item.taxConceptId.equals(fixture.concept.id) && item.period.equals(request.period()))).hasSize(1);
        assertThat(debts.findByLiquidationId(liquidationId)).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from debt where liquidation_id=?", Long.class, liquidationId)).isOne();
        assertThat(idempotencyRows(actor, "POST:/liquidations", key)).isOne();

        MvcResult retry = httpPost("/api/v1/liquidations", auth, key, body);
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first);
        assertThat(liquidations.findByTaxpayerId(fixture.taxpayer.id).stream()
            .filter(item -> item.taxConceptId.equals(fixture.concept.id) && item.period.equals(request.period()))).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from debt where liquidation_id=?", Long.class, liquidationId)).isOne();
        assertThat(idempotencyRows(actor, "POST:/liquidations", key)).isOne();

        var changed = new ApiDtos.LiquidationRequest(fixture.taxpayer.id, fixture.concept.id,
            request.period(), BigDecimal.ONE, request.dueDate());
        mvc.perform(post("/api/v1/liquidations").with(authentication(auth)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(changed)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(liquidations.findByTaxpayerId(fixture.taxpayer.id).stream()
            .filter(item -> item.taxConceptId.equals(fixture.concept.id) && item.period.equals(request.period()))).hasSize(1);
    }

    @Test
    void paymentReplayReturnsOriginalResponseAfterLaterAllocation() throws Exception {
        Fixture fixture = fixture("PAYMENT_REPLAY");
        var auth = identity("payment-replay-" + UUID.randomUUID(), null, "RENTAS");
        var request = new ApiDtos.RegisterPaymentRequest(fixture.taxpayer.id, PaymentMethod.CASH,
            new BigDecimal("50.00"), List.of());
        String key = "payment-replay-" + UUID.randomUUID();
        String body = json.writeValueAsString(request);

        MvcResult created = httpPost("/api/v1/payments", auth, key, body);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String originalResponse = created.getResponse().getContentAsString();
        long paymentId = json.readTree(originalResponse).path("id").asLong();

        setActor(auth);
        paymentService.allocateExisting(paymentId,
            new ApiDtos.AllocationRequest(fixture.debtId, new BigDecimal("10.00")));

        MvcResult replay = httpPost("/api/v1/payments", auth, key, body);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(json.readTree(replay.getResponse().getContentAsString()))
            .isEqualTo(json.readTree(originalResponse));
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("allocatedAmount").decimalValue())
            .isEqualByComparingTo("0.00");
        assertThat(payments.findById(paymentId).orElseThrow().allocatedAmount).isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("select count(*) from payment where taxpayer_id=? and idempotency_key=?",
            Long.class, fixture.taxpayer.id, key)).isOne();
        assertThat(idempotencyRows(auth.getName(), "POST:/payments", key)).isOne();
    }

    @Test
    void billReplayReturnsOriginalExpirySnapshotAfterDueDateChanges() throws Exception {
        Fixture fixture = fixture("BILL_REPLAY");
        var auth = identity("bill-replay-" + UUID.randomUUID(), null, "RENTAS");
        var request = new ApiDtos.CreateBillRequest(fixture.taxpayer.id, List.of(fixture.debtId), dates.today().plusDays(30));
        String key = "bill-replay-" + UUID.randomUUID();
        String body = json.writeValueAsString(request);

        MvcResult created = httpPost("/api/v1/bills", auth, key, body);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode originalResponse = json.readTree(created.getResponse().getContentAsString());
        assertThat(originalResponse.path("expired").asBoolean()).isFalse();
        long billId = originalResponse.path("id").asLong();

        Bill bill = bills.findById(billId).orElseThrow();
        bill.dueDate = dates.today().minusDays(1);
        bills.saveAndFlush(bill);

        MvcResult replay = httpPost("/api/v1/bills", auth, key, body);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(json.readTree(replay.getResponse().getContentAsString())).isEqualTo(originalResponse);
        assertThat(bills.findById(billId).orElseThrow().dueDate).isBefore(dates.today());
        assertThat(idempotencyRows(auth.getName(), "POST:/bills", key)).isOne();
    }

    @Test
    void dueDateReplayReturnsOriginalAcceptedResponseAfterWorkerCompletes() throws Exception {
        var auth = identity("due-date-replay-" + UUID.randomUUID(), null, "RENTAS");
        LocalDate processingDate = LocalDate.of(1801, 1, 1);
        while (dueDateRuns.findByProcessingDate(processingDate).isPresent()) processingDate = processingDate.plusDays(1);
        var request = new FiscalProcessingController.DueDateRequest(processingDate);
        String key = "due-date-replay-" + UUID.randomUUID();
        String body = json.writeValueAsString(request);

        MvcResult accepted = httpPost("/api/v1/administration/process-due-dates", auth, key, body);
        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
        JsonNode originalResponse = json.readTree(accepted.getResponse().getContentAsString());
        long runId = originalResponse.path("id").asLong();
        assertThat(originalResponse.path("status").asText()).isEqualTo("PENDING");

        dueDateWorker.processNext();
        assertThat(dueDates.get(runId).status()).isEqualTo(DueDateRunStatus.COMPLETED);

        MvcResult replay = httpPost("/api/v1/administration/process-due-dates", auth, key, body);
        assertThat(replay.getResponse().getStatus()).isEqualTo(202);
        assertThat(json.readTree(replay.getResponse().getContentAsString())).isEqualTo(originalResponse);
        assertThat(dueDateRuns.findByProcessingDate(processingDate)).isPresent();
        assertThat(idempotencyRows(auth.getName(), "POST:/administration/process-due-dates", key)).isOne();
    }

    @Test
    void exemptionCreationConcurrentReplayKeepsOneRequestAndOneOutboxEvent() throws Exception {
        Fixture fixture = fixture("EXEMPTION");
        fixture.taxpayer.canonicalCitizenId = UUID.randomUUID();
        taxpayers.saveAndFlush(fixture.taxpayer);
        var auth = identity("exemption-idempotency-" + UUID.randomUUID(), fixture.taxpayer.id, "TAXPAYER");
        String key = "exemption-" + UUID.randomUUID();
        var request = new ApiDtos.CreateExemptionRequest(fixture.taxpayer.id, fixture.concept.id, "Solicitud sintética",
            new BigDecimal("20.00"), LocalDate.now().minusDays(1), LocalDate.now().plusDays(30));
        String body = json.writeValueAsString(request);

        List<MvcResult> responses = runTogether(
            () -> httpPost("/api/v1/exemption-requests", auth, key, body),
            () -> httpPost("/api/v1/exemption-requests", auth, key, body));

        String first = responses.get(0).getResponse().getContentAsString();
        assertThat(responses).allSatisfy(response -> assertThat(response.getResponse().getStatus()).isEqualTo(201));
        assertThat(responses.get(1).getResponse().getContentAsString()).isEqualTo(first);
        long requestId = json.readTree(first).path("id").asLong();
        assertThat(exemptions.findAll().stream().filter(item -> item.taxpayerId.equals(fixture.taxpayer.id)
            && item.taxConceptId.equals(fixture.concept.id))).hasSize(1);
        assertThat(outbox.findAll().stream().filter(event -> event.eventType.equals("exemptionRequested")
            && event.aggregateId.equals(Long.toString(requestId)))).hasSize(1);
        assertThat(idempotencyRows(auth.getName(), "POST:/exemption-requests", key)).isOne();

        var changed = new ApiDtos.CreateExemptionRequest(fixture.taxpayer.id, fixture.concept.id, "Solicitud sintética",
            new BigDecimal("25.00"), request.validFrom(), request.validUntil());
        mvc.perform(post("/api/v1/exemption-requests").with(authentication(auth)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(changed)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(exemptions.findAll().stream().filter(item -> item.taxpayerId.equals(fixture.taxpayer.id)
            && item.taxConceptId.equals(fixture.concept.id))).hasSize(1);
    }

    @Test
    void planGrantConcurrentReplayCreatesOnePlanAndInstallmentSet() throws Exception {
        Fixture fixture = fixture("PLAN_GRANT");
        plansConfiguration();
        var taxpayerAuth = identity("plan-grant-requester-" + UUID.randomUUID(), fixture.taxpayer.id, "TAXPAYER");
        setActor(taxpayerAuth);
        PaymentPlanRequest pending = planWorkflow.request(new ApiDtos.CreatePaymentPlanRequest(
            fixture.taxpayer.id, List.of(fixture.debtId), 3));

        var rentasAuth = identity("plan-grant-approver-" + UUID.randomUUID(), null, "RENTAS");
        String key = "plan-grant-" + UUID.randomUUID();
        var grant = new ApiDtos.GrantPaymentPlanRequest(BigDecimal.ZERO);
        String body = json.writeValueAsString(grant);
        String path = "/api/v1/payment-plan-requests/" + pending.id + "/grant";

        List<MvcResult> responses = runTogether(
            () -> httpPost(path, rentasAuth, key, body),
            () -> httpPost(path, rentasAuth, key, body));

        String first = responses.get(0).getResponse().getContentAsString();
        assertThat(responses).allSatisfy(response -> assertThat(response.getResponse().getStatus()).isEqualTo(200));
        assertThat(responses.get(1).getResponse().getContentAsString()).isEqualTo(first);
        long planId = json.readTree(first).path("paymentPlanId").asLong();
        assertThat(planId).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from payment_plan where request_id=?", Long.class, pending.id)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from installment where payment_plan_id=?", Long.class, planId)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("select count(*) from payment_plan_request where id=? and status='GRANTED' and payment_plan_id=?",
            Long.class, pending.id, planId)).isOne();
        assertThat(idempotencyRows(rentasAuth.getName(), "POST:/payment-plan-requests/{id}/grant:" + pending.id, key)).isOne();

        mvc.perform(post(path).with(authentication(rentasAuth)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new ApiDtos.GrantPaymentPlanRequest(BigDecimal.ONE))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select count(*) from payment_plan where request_id=?", Long.class, pending.id)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from installment where payment_plan_id=?", Long.class, planId)).isEqualTo(3L);
    }

    @Test
    void exemptionApprovalConcurrentReplayCreatesOneExemptionAndOutboxUpdate() throws Exception {
        Fixture fixture = fixture("EXEMPTION_APPROVAL");
        fixture.taxpayer.canonicalCitizenId = UUID.randomUUID();
        taxpayers.saveAndFlush(fixture.taxpayer);
        LocalDate today = dates.today();
        var requester = identity("exemption-approval-requester-" + UUID.randomUUID(), fixture.taxpayer.id, "TAXPAYER");
        setActor(requester);
        var request = exemptionService.create(new ApiDtos.CreateExemptionRequest(fixture.taxpayer.id, fixture.concept.id,
            "Solicitud sintética", new BigDecimal("20.00"), today.minusDays(1), today.plusDays(30)));
        var reviewer = identity("exemption-approval-reviewer-" + UUID.randomUUID(), null, "RENTAS");
        setActor(reviewer);
        exemptionService.start(request.id);
        exemptionService.submit(request.id);

        var approver = identity("exemption-approval-supervisor-" + UUID.randomUUID(), null, "SUPERVISOR");
        String key = "exemption-approval-" + UUID.randomUUID();
        String path = "/api/v1/exemption-requests/" + request.id + "/approve";
        List<MvcResult> responses = runTogether(
            () -> httpPost(path, approver, key, "null"),
            () -> httpPost(path, approver, key, "null"));

        String first = responses.get(0).getResponse().getContentAsString();
        assertThat(responses).allSatisfy(response -> assertThat(response.getResponse().getStatus()).isEqualTo(200));
        assertThat(responses.get(1).getResponse().getContentAsString()).isEqualTo(first);
        long exemptionId = json.readTree(first).path("id").asLong();
        assertThat(jdbc.queryForObject("select count(*) from exemption where request_id=?", Long.class, request.id)).isOne();
        assertThat(outbox.findAll().stream().filter(event -> event.eventType.equals("updateExemptionStatus")
            && event.aggregateId.equals(Long.toString(request.id)))).hasSize(1);
        assertThat(idempotencyRows(approver.getName(), "POST:/exemption-requests/{id}/approve:" + request.id, key)).isOne();
        assertThat(exemptionId).isPositive();
    }

    @Test
    void reversalExecutionConcurrentReplayReversesPaymentOnce() throws Exception {
        Fixture fixture = fixture("REVERSAL_EXECUTE");
        setActor(identity("reversal-execute-payment-" + UUID.randomUUID(), null, "RENTAS"));
        var payment = paymentService.register(new ApiDtos.RegisterPaymentRequest(fixture.taxpayer.id,
            PaymentMethod.CASH, new BigDecimal("100.00"), List.of(new ApiDtos.AllocationRequest(fixture.debtId, new BigDecimal("100.00")))));
        var cashier = identity("reversal-execute-requester-" + UUID.randomUUID(), null, "CASHIER");
        setActor(cashier);
        var reversal = reversalService.request(payment.id, "Reversión sintética");
        TestActorSupport.asApprover(() -> reversalService.approve(reversal.id));

        var rentas = identity("reversal-execute-operator-" + UUID.randomUUID(), null, "RENTAS");
        String key = "reversal-execute-" + UUID.randomUUID();
        String path = "/api/v1/payment-reversals/" + reversal.id + "/execute";
        long reversedEventsBefore = outbox.findAll().stream().filter(event -> event.eventType.equals("paymentReversed")).count();
        List<MvcResult> responses = runTogether(() -> httpPost(path, rentas, key, ""), () -> httpPost(path, rentas, key, ""));

        String first = responses.get(0).getResponse().getContentAsString();
        assertThat(responses).allSatisfy(response -> assertThat(response.getResponse().getStatus()).isEqualTo(200));
        assertThat(responses.get(1).getResponse().getContentAsString()).isEqualTo(first);
        assertThat(reversals.findById(reversal.id).orElseThrow().status).isEqualTo(PaymentReversalStatus.EXECUTED);
        assertThat(jdbc.queryForObject("select status from payment where id=?", String.class, payment.id)).isEqualTo("REVERSED");
        assertThat(allocations.findByPaymentId(payment.id)).allSatisfy(allocation -> assertThat(allocation.status).isEqualTo("REVERSED"));
        assertThat(outbox.findAll().stream().filter(event -> event.eventType.equals("paymentReversed")).count())
            .isEqualTo(reversedEventsBefore);
        assertThat(idempotencyRows(rentas.getName(), "POST:/payment-reversals/{id}/execute:" + reversal.id, key)).isOne();
    }

    @Test
    void liquidationRunExecutionConcurrentReplayCreatesOneLiquidationPerItem() throws Exception {
        Fixture fixture = fixture("RUN_EXECUTE");
        String suffix = UUID.randomUUID().toString();
        setActor(identity("run-execute-fixture-" + suffix, null, "RENTAS"));
        TaxpayerReference runTaxpayer = catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
            TaxpayerType.CITIZEN, "RUN-" + suffix, suffix.replace("-", ""), null, "Synthetic idempotency test"));
        LocalDate today = dates.today();
        var run = liquidationRunService.create(new ApiDtos.CreateLiquidationRunRequest(fixture.concept.id,
            YearMonth.from(today.plusMonths(1)).toString(), today.plusDays(30),
            List.of(new ApiDtos.LiquidationRunItemRequest(runTaxpayer.id, new BigDecimal("100.00")))));
        liquidationRunService.preview(run.id);
        liquidationRunService.submit(run.id);
        TestActorSupport.asApprover(() -> liquidationRunService.approve(run.id, null));

        var rentas = identity("run-execute-operator-" + UUID.randomUUID(), null, "RENTAS");
        String key = "run-execute-" + UUID.randomUUID();
        String path = "/api/v1/liquidation-runs/" + run.id + "/execute";
        List<MvcResult> responses = runTogether(() -> httpPost(path, rentas, key, ""), () -> httpPost(path, rentas, key, ""));

        String first = responses.get(0).getResponse().getContentAsString();
        assertThat(responses).allSatisfy(response -> assertThat(response.getResponse().getStatus()).isEqualTo(200));
        assertThat(responses.get(1).getResponse().getContentAsString()).isEqualTo(first);
        assertThat(liquidationRuns.findById(run.id).orElseThrow().status).isEqualTo(LiquidationRunStatus.EXECUTED);
        var items = liquidationRunItems.findByLiquidationRunIdOrderById(run.id);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).status).isEqualTo(LiquidationRunItemStatus.LIQUIDATED);
        assertThat(items.get(0).liquidationId).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from liquidation where id=?", Long.class, items.get(0).liquidationId)).isOne();
        assertThat(idempotencyRows(rentas.getName(), "POST:/liquidation-runs/{id}/execute:" + run.id, key)).isOne();
    }

    @Test
    void lateChargeApplicationConcurrentReplayCreatesOneApplicationAndTwoAdjustments() throws Exception {
        Fixture fixture = fixture("LATE_CHARGE");
        LocalDate today = dates.today();
        var debt = debts.findById(fixture.debtId).orElseThrow();
        debt.dueDate = today.minusDays(2);
        debts.saveAndFlush(debt);
        var rule = new LateChargeRule();
        rule.code = "IDEMPOTENCY_" + UUID.randomUUID();
        rule.surchargeRate = new BigDecimal("10.0000");
        rule.dailyInterestRate = new BigDecimal("0.1000");
        rule.active = true;
        rule.validFrom = today.minusDays(30);
        lateChargeRules.saveAndFlush(rule);

        var rentas = identity("late-charge-operator-" + UUID.randomUUID(), null, "RENTAS");
        String key = "late-charge-" + UUID.randomUUID();
        String path = "/api/v1/debts/" + debt.id + "/late-charges";
        String body = json.writeValueAsString(new FiscalProcessingController.CalculationDateRequest(today));
        BigDecimal balanceBefore = debt.outstandingBalance;
        List<MvcResult> responses = runTogether(() -> httpPost(path, rentas, key, body), () -> httpPost(path, rentas, key, body));

        String first = responses.get(0).getResponse().getContentAsString();
        assertThat(responses).allSatisfy(response -> assertThat(response.getResponse().getStatus()).isEqualTo(200));
        assertThat(responses.get(1).getResponse().getContentAsString()).isEqualTo(first);
        assertThat(lateChargeApplications.findByDebtId(debt.id)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from adjustment_request where debt_id=?", Long.class, debt.id)).isEqualTo(2L);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isGreaterThan(balanceBefore);
        assertThat(idempotencyRows(rentas.getName(), "POST:/debts/{id}/late-charges:" + debt.id, key)).isOne();
    }

    @Test
    void reversalRequestConcurrentReplayReturnsOneRequestAndChangedReasonConflicts() throws Exception {
        Fixture fixture = fixture("REVERSAL");
        setActor(identity("reversal-payment-" + UUID.randomUUID(), null, "RENTAS"));
        var payment = paymentService.register(new ApiDtos.RegisterPaymentRequest(fixture.taxpayer.id,
            PaymentMethod.CASH, new BigDecimal("100.00"), List.of(new ApiDtos.AllocationRequest(fixture.debtId, new BigDecimal("100.00")))));
        var auth = identity("reversal-cashier-" + UUID.randomUUID(), null, "CASHIER");
        String key = "reversal-" + UUID.randomUUID();
        var request = new ApiDtos.CreateReversalRequest("Solicitud sintética");
        String body = json.writeValueAsString(request);
        String path = "/api/v1/payments/" + payment.id + "/reversal-requests";

        List<MvcResult> responses = runTogether(() -> httpPost(path, auth, key, body), () -> httpPost(path, auth, key, body));

        String first = responses.get(0).getResponse().getContentAsString();
        assertThat(responses).allSatisfy(response -> assertThat(response.getResponse().getStatus()).isEqualTo(201));
        assertThat(responses.get(1).getResponse().getContentAsString()).isEqualTo(first);
        long reversalId = json.readTree(first).path("id").asLong();
        assertThat(reversals.findAll().stream().filter(item -> item.paymentId.equals(payment.id))).hasSize(1);
        assertThat(reversals.findById(reversalId)).isPresent();
        assertThat(idempotencyRows(auth.getName(), "POST:/payments/{id}/reversal-requests:" + payment.id, key)).isOne();

        mvc.perform(post(path).with(authentication(auth)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new ApiDtos.CreateReversalRequest("Motivo diferente"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(reversals.findAll().stream().filter(item -> item.paymentId.equals(payment.id))).hasSize(1);
    }

    @Test
    void refinancingRequestConcurrentReplayKeepsOneRequestAndChangedBodyConflicts() throws Exception {
        Fixture fixture = fixture("REFINANCE");
        plansConfiguration();
        setActor(identity("refinancing-plan-request-" + UUID.randomUUID(), fixture.taxpayer.id, "TAXPAYER"));
        PaymentPlanRequest request = planWorkflow.request(new ApiDtos.CreatePaymentPlanRequest(
            fixture.taxpayer.id, List.of(fixture.debtId), 3));
        setActor(identity("refinancing-plan-grant-" + UUID.randomUUID(), null, "RENTAS"));
        PaymentPlanRequest granted = planWorkflow.grant(request.id, new ApiDtos.GrantPaymentPlanRequest(BigDecimal.ZERO));
        assertThat(granted.paymentPlanId).isNotNull();

        var auth = identity("refinancing-owner-" + UUID.randomUUID(), fixture.taxpayer.id, "TAXPAYER");
        String key = "refinancing-" + UUID.randomUUID();
        var body = new ApiDtos.CreateRefinancingRequest(6);
        String payload = json.writeValueAsString(body);
        String path = "/api/v1/payment-plans/" + granted.paymentPlanId + "/refinancing-requests";

        List<MvcResult> responses = runTogether(() -> httpPost(path, auth, key, payload), () -> httpPost(path, auth, key, payload));

        String first = responses.get(0).getResponse().getContentAsString();
        assertThat(responses).allSatisfy(response -> assertThat(response.getResponse().getStatus()).isEqualTo(201));
        assertThat(responses.get(1).getResponse().getContentAsString()).isEqualTo(first);
        long refinancingId = json.readTree(first).path("id").asLong();
        assertThat(refinancings.findAll().stream().filter(item -> item.originalPlanId.equals(granted.paymentPlanId))).hasSize(1);
        assertThat(refinancings.findById(refinancingId)).isPresent();
        assertThat(idempotencyRows(auth.getName(), "POST:/payment-plans/{planId}/refinancing-requests:" + granted.paymentPlanId, key)).isOne();

        mvc.perform(post(path).with(authentication(auth)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new ApiDtos.CreateRefinancingRequest(5))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(refinancings.findAll().stream().filter(item -> item.originalPlanId.equals(granted.paymentPlanId))).hasSize(1);
    }

    private Fixture fixture(String prefix) {
        String suffix = UUID.randomUUID().toString();
        setActor(identity("idempotency-fixture-" + suffix, null, "RENTAS"));
        TaxpayerReference taxpayer = catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
            TaxpayerType.CITIZEN, prefix + "-" + suffix,
            String.format("%08d", Math.floorMod(suffix.hashCode(), 100_000_000)), null, "Synthetic idempotency test"));
        TaxConcept concept = catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
            prefix + "_" + suffix.replace('-', '_'), "Synthetic " + prefix, null, TaxConceptType.FEE, "M5"));
        TaxConfiguration configuration = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
            concept.id, CalculationType.FIXED, null, new BigDecimal("100.00"), null, null, true, true,
            LocalDate.now().minusDays(1), null));
        catalog.submit(configuration.id);
        TestActorSupport.asApprover(() -> catalog.approve(configuration.id));
        var liquidation = liquidationService.create(new ApiDtos.LiquidationRequest(taxpayer.id, concept.id,
            YearMonth.now().toString(), BigDecimal.ZERO, LocalDate.now().plusDays(30)));
        Debt debt = debts.findByLiquidationId(liquidation.id).orElseThrow();
        return new Fixture(taxpayer, concept, debt.id);
    }

    private void plansConfiguration() {
        planWorkflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(
            1, 12, BigDecimal.ZERO, BigDecimal.ZERO, 0, 2, true, true, 2,
            LocalDate.now().minusDays(1), null, true));
    }

    private long idempotencyRows(String actor, String operation, String key) {
        return jdbc.queryForObject("select count(*) from idempotency_request where actor_id=? and operation=? and idempotency_key=?",
            Long.class, actor, operation, key);
    }

    private MvcResult httpPost(String path, UsernamePasswordAuthenticationToken auth, String key, String body) throws Exception {
        return mvc.perform(post(path).with(authentication(auth)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn();
    }

    private static UsernamePasswordAuthenticationToken identity(String actor, Long taxpayerId, String role) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(actor, taxpayerId), null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static void setActor(UsernamePasswordAuthenticationToken auth) {
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static List<MvcResult> runTogether(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = List.of(
                pool.submit(task(first, ready, start)), pool.submit(task(second, ready, start)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<MvcResult> task(Callable<MvcResult> action, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try { return action.call(); }
            finally { SecurityContextHolder.clearContext(); }
        };
    }

    private record Fixture(TaxpayerReference taxpayer, TaxConcept concept, Long debtId) {}
}
