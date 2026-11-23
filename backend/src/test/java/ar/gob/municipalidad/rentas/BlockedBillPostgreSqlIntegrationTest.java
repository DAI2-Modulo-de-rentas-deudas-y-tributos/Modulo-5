package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BlockedBillPostgreSqlIntegrationTest {
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
    @Autowired LiquidationService liquidations;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired DebtRepository debts;
    @Autowired BillRepository bills;
    @Autowired BillDebtRepository billDebts;
    @Autowired BillingService billing;
    @Autowired PaymentService payments;
    @Autowired AdjustmentService adjustmentService;
    @Autowired LateChargeService lateCharges;
    @Autowired FiscalProcessingDatePolicy fiscalDates;
    @Autowired LateChargeRuleRepository lateChargeRules;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ReversalService reversals;
    @Autowired AuditRepository audits;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoUserRepository demoUsers;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService demoAuth;

    @BeforeEach
    void authenticateFixtureSetup() {
        var authorities = List.of("RENTAS", "SUPERVISOR").stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedIdentity("scrum-668-test", null), null, authorities));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void taxpayerBlockedGets409WithoutPersistingBillOrLinks() throws Exception {
        Debt blockedDebt = debtFor(TaxpayerStatus.BLOCKED);
        long billsBefore = bills.count();
        long linksBefore = billDebts.count();

        mvc.perform(post("/api/v1/bills")
                        .header("X-Demo-Session", session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(blockedDebt)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TAXPAYER_BLOCKED"));

        assertThat(bills.count()).isEqualTo(billsBefore);
        assertThat(billDebts.count()).isEqualTo(linksBefore);
        assertThat(jdbc.queryForObject("select count(*) from bill where taxpayer_id = ?", Long.class, blockedDebt.taxpayerId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from bill_debt bd join bill b on b.id = bd.bill_id where b.taxpayer_id = ?", Long.class, blockedDebt.taxpayerId)).isZero();
    }

    @Test
    void activeTaxpayerCanIssueBillAndPersistsItsDebtLink() throws Exception {
        Debt activeDebt = debtFor(TaxpayerStatus.ACTIVE);
        mvc.perform(post("/api/v1/bills")
                        .header("X-Demo-Session", session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(activeDebt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxpayerId").value(activeDebt.taxpayerId));

        assertThat(jdbc.queryForObject("select count(*) from bill where taxpayer_id = ?", Long.class, activeDebt.taxpayerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from bill_debt bd join bill b on b.id = bd.bill_id where b.taxpayer_id = ?", Long.class, activeDebt.taxpayerId)).isEqualTo(1);
    }

    @Test
    void partialSeparatePaymentCancelsOldBarcodeAndReissueUsesRemainingDebtBalance() throws Exception {
        Debt paidPart = debtFor(TaxpayerStatus.ACTIVE);
        Debt other = debtFor(TaxpayerStatus.ACTIVE);
        other.taxpayerId = paidPart.taxpayerId;
        debts.saveAndFlush(other);
        Bill oldBill = billing.create(new ApiDtos.CreateBillRequest(paidPart.taxpayerId,
            List.of(paidPart.id, other.id), LocalDate.now().plusDays(10)));
        long paymentsBefore = paymentRepository.count();

        payments.register(new ApiDtos.RegisterPaymentRequest(paidPart.taxpayerId, null, PaymentMethod.CASH,
            new BigDecimal("40.00"), List.of(new ApiDtos.AllocationRequest(paidPart.id, new BigDecimal("40.00")))));

        Bill cancelled = bills.findById(oldBill.id).orElseThrow();
        assertThat(cancelled.status).isEqualTo(BillStatus.CANCELLED);
        assertThat(cancelled.cancellationReason).isEqualTo(BillLifecycleService.DEBT_BALANCE_CHANGED);
        assertThat(cancelled.totalAmount).isEqualByComparingTo("200.00");
        assertThat(billDebts.findByBillIdOrderByIdAsc(oldBill.id)).hasSize(2);
        assertThat(audits.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc("Bill", oldBill.id.toString()))
            .anySatisfy(entry -> assertThat(entry.action).isEqualTo("BILL_CANCELLED_AFTER_BALANCE_CHANGE"));
        assertThatThrownBy(() -> payments.register(new ApiDtos.RegisterPaymentRequest(paidPart.taxpayerId,
            oldBill.id, PaymentMethod.CASH, new BigDecimal("100.00"),
            List.of(new ApiDtos.AllocationRequest(other.id, new BigDecimal("100.00"))))))
            .isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).code).isEqualTo("BILL_NOT_PAYABLE");
        assertThat(paymentRepository.count()).isEqualTo(paymentsBefore + 1);
        Bill replacement = billing.create(new ApiDtos.CreateBillRequest(paidPart.taxpayerId,
            List.of(paidPart.id, other.id), LocalDate.now().plusDays(10)));
        assertThat(replacement.id).isNotEqualTo(oldBill.id);
        assertThat(replacement.totalAmount).isEqualByComparingTo("160.00");
        assertThat(billDebts.findByBillIdOrderByIdAsc(replacement.id)).hasSize(2);
        assertThat(bills.findById(oldBill.id).orElseThrow().status).isEqualTo(BillStatus.CANCELLED);
        mvc.perform(get("/api/v1/bills/{id}", oldBill.id).header("X-Demo-Session", session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancellationReason").value(BillLifecycleService.DEBT_BALANCE_CHANGED))
            .andExpect(jsonPath("$.presentationStatus").doesNotExist());
        mvc.perform(get("/api/v1/bills/{id}/document", oldBill.id).header("X-Demo-Session", session()))
            .andExpect(status().isOk())
            .andExpect(result -> assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.ISO_8859_1))
                .contains("ESTADO: ANULADA", "Motivo: Anulada por cambio en el saldo de una o mas deudas.")
                .doesNotContain("DEBT_BALANCE_CHANGED"));
    }

    @Test
    void payingThroughItsOwnBillAllocatesBeforeCancellingAndRejectsTheOldBillAfterward() {
        Debt debt = debtFor(TaxpayerStatus.ACTIVE);
        Bill bill = billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), LocalDate.now().plusDays(10)));
        long paymentsBefore = paymentRepository.count();

        Payment payment = payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId, bill.id,
            PaymentMethod.CASH, new BigDecimal("40.00"),
            List.of(new ApiDtos.AllocationRequest(debt.id, new BigDecimal("40.00")))));

        assertThat(paymentRepository.findById(payment.id)).isPresent();
        assertThat(paymentRepository.findById(payment.id).orElseThrow().allocatedAmount).isEqualByComparingTo("40.00");
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("60.00");
        Bill cancelled = bills.findById(bill.id).orElseThrow();
        assertThat(cancelled.status).isEqualTo(BillStatus.CANCELLED);
        assertThat(cancelled.cancellationReason).isEqualTo(BillLifecycleService.DEBT_BALANCE_CHANGED);

        assertThatThrownBy(() -> payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId, bill.id,
            PaymentMethod.CASH, new BigDecimal("60.00"),
            List.of(new ApiDtos.AllocationRequest(debt.id, new BigDecimal("60.00"))))))
            .isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).code).isEqualTo("BILL_NOT_PAYABLE");
        assertThat(paymentRepository.count()).isEqualTo(paymentsBefore + 1);
        assertThat(billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), LocalDate.now().plusDays(10))).totalAmount).isEqualByComparingTo("60.00");
        assertThat(bills.findById(bill.id).orElseThrow().status).isEqualTo(BillStatus.CANCELLED);
    }

    @Test
    void fullConfirmedPaymentThroughOwnBillIsPresentedAsPaidWithoutChangingTechnicalCancellation() throws Exception {
        Debt debt = debtFor(TaxpayerStatus.ACTIVE);
        Bill bill = billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), LocalDate.now().plusDays(10)));

        payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId, bill.id, PaymentMethod.CASH,
            new BigDecimal("100.00"), List.of(new ApiDtos.AllocationRequest(debt.id, new BigDecimal("100.00")))));

        Bill technical = bills.findById(bill.id).orElseThrow();
        assertThat(technical.status).isEqualTo(BillStatus.CANCELLED);
        assertThat(technical.cancellationReason).isEqualTo(BillLifecycleService.DEBT_BALANCE_CHANGED);
        mvc.perform(get("/api/v1/bills/{id}", bill.id).header("X-Demo-Session", session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.presentationStatus").value("PAID"));
        mvc.perform(get("/api/v1/taxpayers/{id}/bills?size=100", debt.taxpayerId).header("X-Demo-Session", session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].presentationStatus").value("PAID"));
        String pdf = new String(mvc.perform(get("/api/v1/bills/{id}/document", bill.id)
                .header("X-Demo-Session", session())).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.ISO_8859_1);
        assertThat(pdf).contains("ESTADO: PAGADA").doesNotContain("DEBT_BALANCE_CHANGED", "Motivo: Anulada");
    }

    @Test
    void partialOwnBillPaymentRemainsPartialEvenIfAnotherChannelSettlesTheRemainingDebt() throws Exception {
        Debt debt = debtFor(TaxpayerStatus.ACTIVE);
        Bill bill = billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), LocalDate.now().plusDays(10)));

        payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId, bill.id, PaymentMethod.CASH,
            new BigDecimal("40.00"), List.of(new ApiDtos.AllocationRequest(debt.id, new BigDecimal("40.00")))));
        mvc.perform(get("/api/v1/bills/{id}", bill.id).header("X-Demo-Session", session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.presentationStatus").value("PARTIAL_PAYMENT"));

        authenticateFixtureSetup();
        payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId, null, PaymentMethod.TRANSFER,
            new BigDecimal("60.00"), List.of(new ApiDtos.AllocationRequest(debt.id, new BigDecimal("60.00")))));

        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isZero();
        mvc.perform(get("/api/v1/bills/{id}", bill.id).header("X-Demo-Session", session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.presentationStatus").value("PARTIAL_PAYMENT"));
    }

    @Test
    void reversalOfOwnBillPaymentIsPresentedAsReversedAndDoesNotReactivateBill() throws Exception {
        Debt debt = debtFor(TaxpayerStatus.ACTIVE);
        Bill bill = billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), LocalDate.now().plusDays(10)));
        Payment payment = payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId, bill.id,
            PaymentMethod.CASH, new BigDecimal("100.00"),
            List.of(new ApiDtos.AllocationRequest(debt.id, new BigDecimal("100.00")))));
        PaymentReversalRequest reversal = reversals.request(payment.id, "Reversión de prueba");
        TestActorSupport.asApprover(() -> reversals.approve(reversal.id));

        reversals.execute(reversal.id);

        assertThat(bills.findById(bill.id).orElseThrow().status).isEqualTo(BillStatus.CANCELLED);
        mvc.perform(get("/api/v1/bills/{id}", bill.id).header("X-Demo-Session", session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.presentationStatus").value("PAYMENT_REVERSED"));
        authenticateFixtureSetup();
        assertThat(billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), LocalDate.now().plusDays(10))).totalAmount).isEqualByComparingTo("100.00");
        assertThat(bills.findById(bill.id).orElseThrow().status).isEqualTo(BillStatus.CANCELLED);
    }

    @Test
    void payingOneDebtFromMultiDebtBillCancelsOldBillAndReissuesOnlyTheUnpaidDebt() {
        Debt paid = debtFor(TaxpayerStatus.ACTIVE);
        Debt remaining = debtFor(TaxpayerStatus.ACTIVE);
        remaining.taxpayerId = paid.taxpayerId;
        debts.saveAndFlush(remaining);
        Bill oldBill = billing.create(new ApiDtos.CreateBillRequest(paid.taxpayerId,
            List.of(paid.id, remaining.id), LocalDate.now().plusDays(10)));

        payments.register(new ApiDtos.RegisterPaymentRequest(paid.taxpayerId, null, PaymentMethod.CASH,
            new BigDecimal("100.00"), List.of(new ApiDtos.AllocationRequest(paid.id, new BigDecimal("100.00")))));

        assertThat(bills.findById(oldBill.id).orElseThrow().status).isEqualTo(BillStatus.CANCELLED);
        assertThat(debts.findById(paid.id).orElseThrow().status).isEqualTo(DebtStatus.PAID);
        assertThatThrownBy(() -> payments.register(new ApiDtos.RegisterPaymentRequest(paid.taxpayerId,
            oldBill.id, PaymentMethod.CASH, new BigDecimal("100.00"),
            List.of(new ApiDtos.AllocationRequest(remaining.id, new BigDecimal("100.00"))))))
            .isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).code).isEqualTo("BILL_NOT_PAYABLE");

        Bill replacement = billing.create(new ApiDtos.CreateBillRequest(paid.taxpayerId,
            List.of(remaining.id), LocalDate.now().plusDays(10)));
        assertThat(replacement.totalAmount).isEqualByComparingTo("100.00");
        assertThat(billDebts.findByBillIdOrderByIdAsc(replacement.id)).extracting(link -> link.debtId)
            .containsExactly(remaining.id);
    }

    @Test
    void concurrentPaymentsUsingSameBillAcceptOnlyOneAndKeepTheOtherDebtUnchanged() throws Exception {
        Debt first = debtFor(TaxpayerStatus.ACTIVE);
        Debt second = debtFor(TaxpayerStatus.ACTIVE);
        second.taxpayerId = first.taxpayerId;
        debts.saveAndFlush(second);
        Bill bill = billing.create(new ApiDtos.CreateBillRequest(first.taxpayerId,
            List.of(first.id, second.id), LocalDate.now().plusDays(10)));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> one = pool.submit(() -> payBillAfter(start, first, bill));
            Future<Boolean> two = pool.submit(() -> payBillAfter(start, second, bill));
            start.countDown();
            assertThat(List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        assertThat(bills.findById(bill.id).orElseThrow().status).isEqualTo(BillStatus.CANCELLED);
        assertThat(paymentRepository.findAll().stream().filter(payment -> bill.id.equals(payment.billId)).count()).isEqualTo(1);
        assertThat(List.of(debts.findById(first.id).orElseThrow(), debts.findById(second.id).orElseThrow()))
            .filteredOn(debt -> debt.status == DebtStatus.PAID).hasSize(1);
    }

    @Test
    void concurrentBillIssuanceForOneDebtLeavesExactlyOneIssuedBill() throws Exception {
        Debt debt = debtFor(TaxpayerStatus.ACTIVE);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> one = pool.submit(() -> issueBillAfter(start, debt));
            Future<Boolean> two = pool.submit(() -> issueBillAfter(start, debt));
            start.countDown();
            assertThat(List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        List<Bill> issued = bills.findAll().stream().filter(bill -> bill.status == BillStatus.ISSUED)
            .filter(bill -> billDebts.findByBillId(bill.id).stream().anyMatch(link -> link.debtId.equals(debt.id)))
            .toList();
        assertThat(issued).hasSize(1);
    }

    @Test
    void approvedAdjustmentCancelsIssuedBillAndAllowsReissueAtUpdatedBalance() {
        Debt debt = debtFor(TaxpayerStatus.ACTIVE);
        Bill oldBill = billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), LocalDate.now().plusDays(10)));
        AdjustmentRequest adjustment = adjustmentService.create(new ApiDtos.CreateAdjustmentRequest(
            debt.id, AdjustmentType.DISCOUNT, new BigDecimal("10.00"), "SCRUM-856 ajuste aprobado"));

        TestActorSupport.asApprover(() -> adjustmentService.approve(adjustment.id, "Aprobado"));

        Bill cancelled = bills.findById(oldBill.id).orElseThrow();
        assertThat(cancelled.status).isEqualTo(BillStatus.CANCELLED);
        assertThat(cancelled.cancellationReason).isEqualTo(BillLifecycleService.DEBT_BALANCE_CHANGED);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("90.00");
        assertThat(audits.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc("Bill", oldBill.id.toString()))
            .anySatisfy(entry -> assertThat(entry.action).isEqualTo("BILL_CANCELLED_AFTER_BALANCE_CHANGE"));
        Bill replacement = billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), LocalDate.now().plusDays(10)));
        assertThat(replacement.totalAmount).isEqualByComparingTo("90.00");
        assertThat(bills.findById(oldBill.id).orElseThrow().status).isEqualTo(BillStatus.CANCELLED);
    }

    @Test
    void rejectedAndNoOpAdjustmentsKeepIssuedBillValid() {
        Debt rejectedDebt = debtFor(TaxpayerStatus.ACTIVE);
        Bill rejectedBill = billing.create(new ApiDtos.CreateBillRequest(rejectedDebt.taxpayerId,
            List.of(rejectedDebt.id), LocalDate.now().plusDays(10)));
        AdjustmentRequest rejected = adjustmentService.create(new ApiDtos.CreateAdjustmentRequest(
            rejectedDebt.id, AdjustmentType.DISCOUNT, new BigDecimal("10.00"), "Solicitud rechazada"));
        TestActorSupport.asApprover(() -> adjustmentService.reject(rejected.id, "No corresponde"));
        assertThat(bills.findById(rejectedBill.id).orElseThrow().status).isEqualTo(BillStatus.ISSUED);

        Debt unchangedDebt = debtFor(TaxpayerStatus.ACTIVE);
        Bill unchangedBill = billing.create(new ApiDtos.CreateBillRequest(unchangedDebt.taxpayerId,
            List.of(unchangedDebt.id), LocalDate.now().plusDays(10)));
        AdjustmentRequest noOp = adjustmentService.create(new ApiDtos.CreateAdjustmentRequest(
            unchangedDebt.id, AdjustmentType.CORRECTION, unchangedDebt.currentAmount, "Sin cambio"));
        TestActorSupport.asApprover(() -> adjustmentService.approve(noOp.id, "Aprobado sin variación"));
        assertThat(debts.findById(unchangedDebt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
        assertThat(bills.findById(unchangedBill.id).orElseThrow().status).isEqualTo(BillStatus.ISSUED);
    }

    @Test
    void appliedLateChargeCancelsIssuedBillAndReissueUsesUpdatedBalance() {
        Debt debt = debtFor(TaxpayerStatus.ACTIVE);
        LocalDate processingDate = fiscalDates.today();
        debt.dueDate = processingDate.minusDays(5);
        debts.saveAndFlush(debt);
        Bill oldBill = billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), processingDate.plusDays(10)));
        LateChargeRule rule = new LateChargeRule();
        rule.code = "SCRUM-856-" + UUID.randomUUID();
        rule.surchargeRate = new BigDecimal("10.0000");
        rule.dailyInterestRate = new BigDecimal("1.0000");
        rule.active = true;
        rule.validFrom = processingDate.minusDays(10);
        lateChargeRules.saveAndFlush(rule);

        var applied = lateCharges.apply(debt.id, processingDate);

        assertThat(applied.applied()).isTrue();
        assertThat(applied.totalAdjustment()).isEqualByComparingTo("15.00");
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("115.00");
        assertThat(bills.findById(oldBill.id).orElseThrow().status).isEqualTo(BillStatus.CANCELLED);
        assertThat(bills.findById(oldBill.id).orElseThrow().cancellationReason)
            .isEqualTo(BillLifecycleService.DEBT_BALANCE_CHANGED);
        Bill replacement = billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), processingDate.plusDays(10)));
        assertThat(replacement.totalAmount).isEqualByComparingTo("115.00");

        Debt zeroChangeDebt = debtFor(TaxpayerStatus.ACTIVE);
        zeroChangeDebt.dueDate = processingDate.minusDays(5);
        debts.saveAndFlush(zeroChangeDebt);
        Bill unaffectedBill = billing.create(new ApiDtos.CreateBillRequest(zeroChangeDebt.taxpayerId,
            List.of(zeroChangeDebt.id), processingDate.plusDays(10)));
        LateChargeRule zeroRule = new LateChargeRule();
        zeroRule.code = "SCRUM-856-ZERO-" + UUID.randomUUID();
        zeroRule.surchargeRate = BigDecimal.ZERO;
        zeroRule.dailyInterestRate = BigDecimal.ZERO;
        zeroRule.active = true;
        zeroRule.validFrom = processingDate;
        lateChargeRules.saveAndFlush(zeroRule);

        assertThat(lateCharges.apply(zeroChangeDebt.id, processingDate).totalAdjustment()).isZero();
        assertThat(bills.findById(unaffectedBill.id).orElseThrow().status).isEqualTo(BillStatus.ISSUED);
    }

    @Test
    void concurrentBillPaymentAndAdjustmentNeverLeaveAnIssuedBillWithStaleBalance() throws Exception {
        Debt debt = debtFor(TaxpayerStatus.ACTIVE);
        Bill bill = billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), LocalDate.now().plusDays(10)));
        AdjustmentRequest adjustment = adjustmentService.create(new ApiDtos.CreateAdjustmentRequest(
            debt.id, AdjustmentType.DISCOUNT, new BigDecimal("10.00"), "Ajuste concurrente"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> payment = pool.submit(() -> payPartialBillAfter(start, debt, bill));
            Future<Boolean> approval = pool.submit(() -> approveAdjustmentAfter(start, adjustment));
            start.countDown();
            payment.get(30, TimeUnit.SECONDS);
            assertThat(approval.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        Bill finalBill = bills.findById(bill.id).orElseThrow();
        assertThat(finalBill.status).isEqualTo(BillStatus.CANCELLED);
        assertThat(bills.findAll().stream().filter(item -> item.status == BillStatus.ISSUED)
            .filter(item -> billDebts.findByBillId(item.id).stream().anyMatch(link -> link.debtId.equals(debt.id)))
            .count()).isZero();
        assertThat(paymentRepository.findAll().stream().filter(item -> bill.id.equals(item.billId)).count())
            .isLessThanOrEqualTo(1);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isIn(
            new BigDecimal("90.00"), new BigDecimal("50.00"));
    }

    private boolean payPartialBillAfter(CountDownLatch start, Debt debt, Bill bill) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("scrum-856-concurrent-payer", debt.taxpayerId), null,
            List.of(new SimpleGrantedAuthority("ROLE_RENTAS"))));
        try {
            payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId, bill.id, PaymentMethod.CASH,
                new BigDecimal("40.00"), List.of(new ApiDtos.AllocationRequest(debt.id, new BigDecimal("40.00")))));
            return true;
        } catch (BusinessException error) {
            if ("BILL_NOT_PAYABLE".equals(error.code)) return false;
            throw error;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean approveAdjustmentAfter(CountDownLatch start, AdjustmentRequest adjustment) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("fixture-supervisor", null), null,
            List.of(new SimpleGrantedAuthority("ROLE_RENTAS"), new SimpleGrantedAuthority("ROLE_SUPERVISOR"))));
        try {
            adjustmentService.approve(adjustment.id, "Aprobado concurrentemente");
            return true;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean issueBillAfter(CountDownLatch start, Debt debt) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("scrum-845-issuer", debt.taxpayerId), null,
            List.of(new SimpleGrantedAuthority("ROLE_RENTAS"))));
        try {
            billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId, List.of(debt.id), LocalDate.now().plusDays(10)));
            return true;
        } catch (BusinessException error) {
            if ("DEBT_ALREADY_BILLED".equals(error.code)) return false;
            throw error;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void reversalKeepsOldBillCancelledAndAllowsReissueForRestoredBalance() {
        Debt debt = debtFor(TaxpayerStatus.ACTIVE);
        Bill oldBill = billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), LocalDate.now().plusDays(10)));
        Payment payment = payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId, null,
            PaymentMethod.CASH, new BigDecimal("40.00"),
            List.of(new ApiDtos.AllocationRequest(debt.id, new BigDecimal("40.00")))));
        assertThat(bills.findById(oldBill.id).orElseThrow().status).isEqualTo(BillStatus.CANCELLED);
        Bill replacement = billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), LocalDate.now().plusDays(10)));

        PaymentReversalRequest reversal = reversals.request(payment.id, "Regresión SCRUM-845");
        TestActorSupport.asApprover(() -> reversals.approve(reversal.id));
        reversals.execute(reversal.id);

        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
        assertThat(bills.findById(oldBill.id).orElseThrow().status).isEqualTo(BillStatus.CANCELLED);
        assertThat(bills.findById(replacement.id).orElseThrow().status).isEqualTo(BillStatus.CANCELLED);
        Bill afterReversal = billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,
            List.of(debt.id), LocalDate.now().plusDays(10)));
        assertThat(afterReversal.totalAmount).isEqualByComparingTo("100.00");
    }

    private boolean payBillAfter(CountDownLatch start, Debt debt, Bill bill) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_RENTAS"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("scrum-845-concurrent", debt.taxpayerId), null, authorities));
        try {
            payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId, bill.id, PaymentMethod.CASH,
                new BigDecimal("100.00"), List.of(new ApiDtos.AllocationRequest(debt.id, new BigDecimal("100.00")))));
            return true;
        } catch (BusinessException error) {
            if ("BILL_NOT_PAYABLE".equals(error.code) || "DEBT_NOT_PAYABLE".equals(error.code)) return false;
            throw error;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Debt debtFor(TaxpayerStatus status) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        TaxpayerReference taxpayer = new TaxpayerReference();
        taxpayer.taxpayerType = TaxpayerType.CITIZEN;
        taxpayer.externalId = "SCRUM-668-" + suffix;
        taxpayer.dni = suffix.substring(0, 8);
        taxpayer.displayName = "SCRUM-668 " + status;
        taxpayer.externalStatus = status;
        taxpayer.createdAt = taxpayer.updatedAt = OffsetDateTime.now();
        taxpayer = taxpayers.saveAndFlush(taxpayer);

        TaxConcept concept = catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
                "SCRUM_668_" + suffix, "SCRUM 668 " + suffix, null, TaxConceptType.FEE, "M5"));
        var configuration = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
                concept.id, CalculationType.FIXED, null, new BigDecimal("100"), null, null,
                true, true, LocalDate.now().minusDays(1), null));
        catalog.submit(configuration.id);
        TestActorSupport.asApprover(() -> catalog.approve(configuration.id));
        var liquidation = liquidations.create(new ApiDtos.LiquidationRequest(
                taxpayer.id, concept.id, YearMonth.now().toString(), BigDecimal.ZERO, LocalDate.now().plusDays(30)));
        return debts.findByTaxpayerId(taxpayer.id).stream()
                .filter(debt -> debt.liquidationId.equals(liquidation.id))
                .findFirst().orElseThrow();
    }

    private String session() {
        DemoAuthSessions sessions = new DemoAuthSessions(demoUsers, taxpayers, encoder, demoAuth);
        return sessions.token(DemoRole.RENTAS);
    }

    private String request(Debt debt) {
        return "{\"taxpayerId\":" + debt.taxpayerId + ",\"debtIds\":[" + debt.id + "],\"dueDate\":\"" + LocalDate.now().plusDays(10) + "\"}";
    }
}
