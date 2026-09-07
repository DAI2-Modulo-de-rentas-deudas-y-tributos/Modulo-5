package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/** SCRUM-117/418: transacciones reales; ninguna transacción de test oculta un rollback. */
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:bulk-stability;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class BulkStabilityTests {
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired LiquidationRunService runs;
    @Autowired LiquidationRepository liquidationRepository;
    @Autowired LiquidationRunRepository runRepository;
    @Autowired DebtRepository debts;
    @Autowired PlatformTransactionManager transactions;

    record Fixture(Long runId, Long conceptId, List<Long> taxpayers) {}

    @BeforeEach
    void authenticate() {
        var roles = List.of("RENTAS", "SUPERVISOR").stream().map(x -> new SimpleGrantedAuthority("ROLE_" + x)).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("qa-bulk", null), null, roles));
    }

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @ValueSource(ints = {10, 50, 200})
    void volumePreservesPartialErrorsAmountsAndSingleExecution(int size) {
        Fixture fixture = fixture(size, 2);
        long beforeLiquidations = liquidationRepository.count(), beforeDebts = debts.count();
        assertTimeout(Duration.ofSeconds(60), () -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                var preview = runs.preview(fixture.runId());
                assertThat(preview.run().totalItems).isEqualTo(size + 2);
                assertThat(preview.run().validItems).isEqualTo(size);
                assertThat(preview.run().errorItems).isEqualTo(2);
                assertThat(preview.run().estimatedTotalAmount).isEqualByComparingTo(new BigDecimal("100.25").multiply(BigDecimal.valueOf(size)));
                assertThat(preview.items()).allSatisfy(item -> assertThat(item.liquidationId).isNull());
            }
            assertThat(liquidationRepository.count()).isEqualTo(beforeLiquidations);
            assertThat(debts.count()).isEqualTo(beforeDebts);
            approve(fixture);
            runs.execute(fixture.runId());
            var detail = runs.detail(fixture.runId());
            assertThat(detail.run().status).isEqualTo(LiquidationRunStatus.EXECUTED);
            assertThat(detail.items()).filteredOn(item -> item.status == LiquidationRunItemStatus.ERROR)
                .hasSize(2).allSatisfy(item -> {
                    assertThat(item.errorCode).isNotBlank();
                    assertThat(item.errorMessage).isNotBlank();
                    assertThat(item.liquidationId).isNull();
                });
            List<LiquidationRunItem> emitted = detail.items().stream()
                .filter(item -> item.status == LiquidationRunItemStatus.LIQUIDATED).toList();
            assertThat(emitted).hasSize(size);
            assertThat(emitted.stream().map(item -> item.liquidationId).toList()).doesNotHaveDuplicates();
            for (LiquidationRunItem item : emitted) {
                Liquidation liquidation = liquidationRepository.findById(item.liquidationId).orElseThrow();
                assertThat(liquidation.taxpayerId).isEqualTo(item.taxpayerId);
                assertThat(liquidation.finalAmount).isEqualByComparingTo("100.25");
                assertThat(debts.findByTaxpayerId(item.taxpayerId)).singleElement().satisfies(debt -> {
                    assertThat(debt.liquidationId).isEqualTo(liquidation.id);
                    assertThat(debt.outstandingBalance).isEqualByComparingTo("100.25");
                });
            }
            for (int attempt = 0; attempt < 3; attempt++) {
                businessError(() -> runs.execute(fixture.runId()), "RUN_NOT_APPROVED");
            }
            assertThat(liquidationRepository.count()).isEqualTo(beforeLiquidations + size);
            assertThat(debts.count()).isEqualTo(beforeDebts + size);
        });
    }

    @Test
    void conflictOnLastItemRollsBackEarlierItemsAndRetryDoesNotDuplicate() {
        Fixture fixture = fixture(3, 0);
        runs.preview(fixture.runId());
        approve(fixture);
        // Otro proceso emite el último ítem después de aprobar el preview.
        liquidations.create(new ApiDtos.LiquidationRequest(fixture.taxpayers().get(2), fixture.conceptId(),
            YearMonth.now().toString(), BigDecimal.ZERO, LocalDate.now().plusDays(30)));
        long beforeLiquidations = liquidationRepository.count(), beforeDebts = debts.count();
        for (int attempt = 0; attempt < 2; attempt++) {
            businessError(() -> runs.execute(fixture.runId()), "DUPLICATE_LIQUIDATION");
            assertThat(liquidationRepository.count()).isEqualTo(beforeLiquidations);
            assertThat(debts.count()).isEqualTo(beforeDebts);
            var stored = runs.detail(fixture.runId());
            assertThat(stored.run().status).isEqualTo(LiquidationRunStatus.APPROVED);
            assertThat(stored.items()).allSatisfy(item -> {
                assertThat(item.status).isEqualTo(LiquidationRunItemStatus.VALID);
                assertThat(item.liquidationId).isNull();
            });
        }
    }

    @Test
    void configurationChangeAfterApprovalRollsBackEmission() {
        Fixture fixture = fixture(3, 0);
        runs.preview(fixture.runId());
        approve(fixture);
        activate(fixture.conceptId(), "250.00");
        long before = liquidationRepository.count(), debtCount = debts.count();
        businessError(() -> runs.execute(fixture.runId()), "RUN_CONFIGURATION_CHANGED");
        assertThat(liquidationRepository.count()).isEqualTo(before);
        assertThat(debts.count()).isEqualTo(debtCount);
        assertThat(runs.detail(fixture.runId()).run().status).isEqualTo(LiquidationRunStatus.APPROVED);
    }

    @Test
    void allInvalidItemsCannotBeSubmittedOrExecuted() {
        Fixture fixture = fixture(0, 3);
        long before = liquidationRepository.count();
        var preview = runs.preview(fixture.runId());
        assertThat(preview.run().validItems).isZero();
        assertThat(preview.run().errorItems).isEqualTo(3);
        businessError(() -> runs.submit(fixture.runId()), "RUN_NOT_READY");
        businessError(() -> runs.execute(fixture.runId()), "RUN_NOT_APPROVED");
        assertThat(liquidationRepository.count()).isEqualTo(before);
    }

    @Test
    void draftPendingAndRejectedRunsCannotEmit() {
        Fixture fixture = fixture(2, 0);
        long before = liquidationRepository.count(), debtCount = debts.count();
        businessError(() -> runs.execute(fixture.runId()), "RUN_NOT_APPROVED");
        runs.preview(fixture.runId());
        runs.submit(fixture.runId());
        businessError(() -> runs.execute(fixture.runId()), "RUN_NOT_APPROVED");
        runs.reject(fixture.runId(), "QA: rechazo controlado");
        businessError(() -> runs.execute(fixture.runId()), "RUN_NOT_APPROVED");
        assertThat(liquidationRepository.count()).isEqualTo(before);
        assertThat(debts.count()).isEqualTo(debtCount);
    }

    @Test
    void duplicatePopulationDoesNotPersistAnotherRun() {
        Fixture fixture = fixture(1, 0);
        long before = runRepository.count();
        var item = new ApiDtos.LiquidationRunItemRequest(fixture.taxpayers().get(0), BigDecimal.ZERO);
        businessError(() -> runs.create(new ApiDtos.CreateLiquidationRunRequest(fixture.conceptId(),
            YearMonth.now().toString(), LocalDate.now().plusDays(30), List.of(item, item))), "DUPLICATE_RUN_TAXPAYER");
        assertThat(runRepository.count()).isEqualTo(before);
    }

    private void businessError(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.code).isEqualTo(code));
    }

    @Test
    void simultaneousExecutionCommitsExactlyOnce() throws Exception {
        Fixture fixture = fixture(20, 0);
        runs.preview(fixture.runId()); approve(fixture);
        long before = liquidationRepository.count(), debtCount = debts.count();
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<String> execute = () -> {
            authenticate();
            try {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("No comenzó la prueba concurrente");
                runs.execute(fixture.runId());
                return "EXECUTED";
            } catch (BusinessException error) {
                return error.code;
            } finally { clearIdentity(); }
        };
        try {
            Future<String> first = pool.submit(execute), second = pool.submit(execute);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("EXECUTED", "RUN_NOT_APPROVED");
            assertThat(liquidationRepository.count()).isEqualTo(before + 20);
            assertThat(debts.count()).isEqualTo(debtCount + 20);
            assertThat(runs.detail(fixture.runId()).run().status).isEqualTo(LiquidationRunStatus.EXECUTED);
        } finally { pool.shutdownNow(); }
    }

    private void approve(Fixture fixture) { runs.submit(fixture.runId()); runs.approve(fixture.runId(), "QA"); }

    private Fixture fixture(int valid, int invalid) {
        return tx(() -> {
            String suffix = UUID.randomUUID().toString();
            TaxConcept concept = catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
                "BULK-" + suffix, "QA masivo", null, TaxConceptType.FEE, "M5"));
            activate(concept.id, "100.25");
            List<Long> ids = new ArrayList<>();
            List<ApiDtos.LiquidationRunItemRequest> population = new ArrayList<>();
            for (int i = 0; i < valid; i++) {
                TaxpayerReference taxpayer = catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
                    TaxpayerType.CITIZEN, suffix + "-" + i, suffix + "-" + i, null, "QA " + i));
                ids.add(taxpayer.id);
                population.add(new ApiDtos.LiquidationRunItemRequest(taxpayer.id, BigDecimal.ZERO));
            }
            for (int i = 0; i < invalid; i++) population.add(new ApiDtos.LiquidationRunItemRequest(Long.MAX_VALUE - i, BigDecimal.ZERO));
            LiquidationRun run = runs.create(new ApiDtos.CreateLiquidationRunRequest(concept.id,
                YearMonth.now().toString(), LocalDate.now().plusDays(30), population));
            return new Fixture(run.id, concept.id, ids);
        });
    }

    private void activate(Long concept, String amount) {
        tx(() -> {
            TaxConfiguration config = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
                concept, CalculationType.FIXED, null, new BigDecimal(amount), null, null,
                true, true, LocalDate.now().minusDays(1), null));
            catalog.submit(config.id); catalog.approve(config.id);
            return config;
        });
    }

    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
}
