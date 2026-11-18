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
            .andExpect(jsonPath("$.cancellationReason").value(BillLifecycleService.DEBT_BALANCE_CHANGED));
        mvc.perform(get("/api/v1/bills/{id}/document", oldBill.id).header("X-Demo-Session", session()))
            .andExpect(status().isOk())
            .andExpect(result -> assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.ISO_8859_1))
                .contains("ESTADO: ANULADA", "Motivo: DEBT_BALANCE_CHANGED"));
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
