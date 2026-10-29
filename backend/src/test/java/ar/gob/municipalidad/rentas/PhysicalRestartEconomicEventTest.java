package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba P4 con dos JVM reales y una única PostgreSQL efímera.
 * Ejecutar con: .\\mvnw.cmd -Dtest=PhysicalRestartEconomicEventTest test
 */
@Testcontainers
class PhysicalRestartEconomicEventTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void deferredM7MovementSurvivesPhysicalRestartAndReconcilesOnce() throws Exception {
        String databaseUrl = POSTGRES.getJdbcUrl();
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(POSTGRES.getDatabaseName()).isEqualTo("test");
        assertThat(POSTGRES.getMappedPort(5432)).isNotEqualTo(5432);
        assertThat(databaseUrl).startsWith("jdbc:postgresql://").contains("/test").doesNotContain("/rentas");

        String suffix = UUID.randomUUID().toString();
        Map<String, String> initial = Map.of(
            "P4_MODE", "prepare",
            "P4_DB_URL", databaseUrl,
            "P4_DB_USER", POSTGRES.getUsername(),
            "P4_DB_PASSWORD", POSTGRES.getPassword(),
            "P4_EXTERNAL_ID", "P4-" + suffix,
            "P4_DNI", "30" + suffix.replace("-", "").substring(0, 6)
        );
        WorkerRun first = runWorker(initial);
        JsonNode beforeRestart = result(first.output());

        assertThat(beforeRestart.path("databaseVersion").asText()).startsWith("PostgreSQL 17.");
        assertThat(beforeRestart.path("paymentStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(beforeRestart.path("deferredStatus").asText()).isEqualTo("PENDING");
        assertThat(beforeRestart.path("deferredReason").asText()).isEqualTo("M7_ORIGINAL_CORRELATION_ID_UNAVAILABLE");
        assertThat(beforeRestart.path("outboxEconomicEvents").asLong()).isZero();
        assertThat(beforeRestart.path("amountApplied").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(beforeRestart.path("historicalBalance").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(beforeRestart.path("originId").asText()).isEqualTo(beforeRestart.path("infractionId").asText());
        assertThat(beforeRestart.path("snapshotHash").asText()).hasSize(64);
        assertThat(beforeRestart.path("snapshotJson").isObject()).isTrue();

        Map<String, String> afterRestart = new HashMap<>(initial);
        afterRestart.put("P4_MODE", "reconcile");
        afterRestart.put("P4_EVENT_ID", beforeRestart.path("sourceEventId").asText());
        afterRestart.put("P4_DEBT_ID", beforeRestart.path("debtId").asText());
        afterRestart.put("P4_PAYMENT_ID", beforeRestart.path("paymentId").asText());
        afterRestart.put("P4_ALLOCATION_ID", beforeRestart.path("allocationId").asText());
        afterRestart.put("P4_DEFERRAL_ID", beforeRestart.path("deferralId").asText());
        afterRestart.put("P4_ORIGINAL_PAYLOAD", beforeRestart.path("originalPayload").asText());
        afterRestart.put("P4_EXPECTED_SNAPSHOT_HASH", beforeRestart.path("snapshotHash").asText());
        WorkerRun second = runWorker(afterRestart);
        JsonNode afterReconcile = result(second.output());

        assertThat(second.pid()).isNotEqualTo(first.pid());
        assertThat(afterReconcile.path("databaseVersion").asText()).startsWith("PostgreSQL 17.");
        assertThat(afterReconcile.path("outcomeWithoutEvidence").asText()).isEqualTo("STILL_DEFERRED");
        assertThat(afterReconcile.path("outcomeAfterRestore").asText()).isEqualTo("PREPARED_IN_OUTBOX");
        assertThat(afterReconcile.path("retryOutcome").asText()).isEqualTo("ALREADY_PREPARED");
        assertThat(afterReconcile.path("paymentId").asText()).isEqualTo(beforeRestart.path("paymentId").asText());
        assertThat(afterReconcile.path("paymentUuid").asText()).isEqualTo(beforeRestart.path("paymentUuid").asText());
        assertThat(afterReconcile.path("debtId").asText()).isEqualTo(beforeRestart.path("debtId").asText());
        assertThat(afterReconcile.path("debtUuid").asText()).isEqualTo(beforeRestart.path("debtUuid").asText());
        assertThat(afterReconcile.path("sourceEventId").asText()).isEqualTo(beforeRestart.path("sourceEventId").asText());
        assertThat(afterReconcile.path("economicEventId").asText()).isEqualTo(beforeRestart.path("economicEventId").asText());
        assertThat(afterReconcile.path("correlationId").asText()).isEqualTo(beforeRestart.path("correlationId").asText());
        assertThat(afterReconcile.path("originId").asText()).isEqualTo(beforeRestart.path("originId").asText());
        assertThat(afterReconcile.path("amountApplied").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(afterReconcile.path("historicalBalance").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(afterReconcile.path("snapshotHash").asText()).isEqualTo(beforeRestart.path("snapshotHash").asText());
        assertThat(afterReconcile.path("paymentsCount").asLong()).isEqualTo(beforeRestart.path("paymentsCount").asLong());
        assertThat(afterReconcile.path("debtsCount").asLong()).isEqualTo(beforeRestart.path("debtsCount").asLong());
        assertThat(afterReconcile.path("allocationsCount").asLong()).isEqualTo(beforeRestart.path("allocationsCount").asLong());
        assertThat(afterReconcile.path("outboxEventsForMovement").asLong()).isEqualTo(1);
        assertThat(afterReconcile.path("deliveryStatus").asText()).isEqualTo("NOT_CONFIRMED_BY_CORE");
        System.out.printf("P4_RESTART_PROOF container=%s pids=%d,%d db=postgres:17.11/<ephemeral>/test event=%s outbox=1 delivery=NOT_CONFIRMED_BY_CORE%n",
            POSTGRES.getContainerId().substring(0, 12), first.pid(), second.pid(), beforeRestart.path("economicEventId").asText());
    }

    private WorkerRun runWorker(Map<String, String> environment) throws Exception {
        String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
            ? "java.exe" : "java";
        String javaCommand = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classpath = testClasspath();
        Path output = Files.createTempFile("m5-p4-worker-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(javaCommand, PhysicalRestartWorker.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile());
            Map<String, String> childEnvironment = builder.environment();
            childEnvironment.remove("DB_URL");
            childEnvironment.remove("DB_USER");
            childEnvironment.remove("DB_PASSWORD");
            childEnvironment.remove("SPRING_DATASOURCE_URL");
            childEnvironment.put("CLASSPATH", classpath);
            childEnvironment.putAll(environment);
            Process process = builder.start();
            if (!process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new AssertionError("El proceso P4 excedió el timeout; pid=" + process.pid());
            }
            String processOutput = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new AssertionError("Worker P4 falló (exit=" + process.exitValue() + "): " + safeOutput(processOutput));
            }
            return new WorkerRun(process.pid(), processOutput);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private JsonNode result(String output) throws Exception {
        return output.lines().filter(line -> line.startsWith(PhysicalRestartWorker.RESULT_PREFIX))
            .map(line -> line.substring(PhysicalRestartWorker.RESULT_PREFIX.length()))
            .findFirst().map(value -> {
                try { return json.readTree(value); }
                catch (IOException ex) { throw new AssertionError("Resultado P4 no es JSON válido", ex); }
            }).orElseThrow(() -> new AssertionError("El worker no emitió resultado P4: " + safeOutput(output)));
    }

    private String testClasspath() throws Exception {
        return System.getProperty("java.class.path");
    }

    private String safeOutput(String output) {
        return output.lines().filter(line -> !line.contains("Using generated security password"))
            .limit(80).reduce((a, b) -> a + System.lineSeparator() + b).orElse("");
    }

    private record WorkerRun(long pid, String output) {}
}
