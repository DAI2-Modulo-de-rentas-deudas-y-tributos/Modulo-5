package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.UUID;

import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

/** Worker de test: cada invocación es una JVM nueva y sólo acepta la PostgreSQL efímera del test padre. */
public class PhysicalRestartWorker {
    static final String RESULT_PREFIX = "P4_WORKER_RESULT=";

    public static void main(String[] args) throws Exception {
        String jdbcUrl = required("P4_DB_URL");
        URI address = isolatedAddress(jdbcUrl);
        System.setProperty("spring.datasource.url", jdbcUrl);
        System.setProperty("spring.datasource.username", required("P4_DB_USER"));
        System.setProperty("spring.datasource.password", required("P4_DB_PASSWORD"));
        System.setProperty("spring.flyway.locations", "classpath:db/migration,classpath:db/migration-postgresql");
        System.setProperty("server.address", "127.0.0.1");
        System.setProperty("server.port", "0");
        System.setProperty("rentas.broker-adapter", "local-log");
        System.setProperty("rentas.outbox-delay-ms", "3600000");
        System.setProperty("logging.level.root", "ERROR");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(RentasApplication.class,
                DisabledSchedulingConfiguration.class).run()) {
            ObjectMapper json = context.getBean(ObjectMapper.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            String version = jdbc.queryForObject("select version()", String.class);
            if (version == null || !version.contains("PostgreSQL 17")) throw new IllegalStateException("La base no es PostgreSQL 17");
            String mode = required("P4_MODE");
            JsonNode result;
            if ("prepare".equals(mode)) result = prepare(context, json, jdbc, version, address);
            else if ("reconcile".equals(mode)) {
                authenticate();
                result = reconcile(context, json, jdbc, version, address);
            }
            else throw new IllegalArgumentException("Modo P4 desconocido");
            System.out.println(RESULT_PREFIX + json.writeValueAsString(result));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static JsonNode prepare(ConfigurableApplicationContext context, ObjectMapper json, JdbcTemplate jdbc,
            String version, URI address) throws Exception {
        CatalogService catalog = context.getBean(CatalogService.class);
        TaxpayerRepository taxpayers = context.getBean(TaxpayerRepository.class);
        TaxConceptRepository concepts = context.getBean(TaxConceptRepository.class);
        ConfirmedM7Consumer consumer = context.getBean(ConfirmedM7Consumer.class);
        ExternalObligationRepository obligations = context.getBean(ExternalObligationRepository.class);
        DebtRepository debts = context.getBean(DebtRepository.class);
        IntegrationEventLogRepository logs = context.getBean(IntegrationEventLogRepository.class);
        PaymentService payments = context.getBean(PaymentService.class);
        PaymentRepository paymentRepository = context.getBean(PaymentRepository.class);
        PaymentAllocationRepository allocations = context.getBean(PaymentAllocationRepository.class);
        OutboxRepository outbox = context.getBean(OutboxRepository.class);

        String dni = required("P4_DNI");
        TaxpayerReference taxpayer = catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,
            required("P4_EXTERNAL_ID"), dni, null, "Contribuyente sintético P4"));
        TaxConcept concept = concepts.findByCode("TRAFFIC_INFRACTION").orElseGet(() -> catalog.createConcept(
            new ApiDtos.CreateTaxConceptRequest("TRAFFIC_INFRACTION", "Infracción de tránsito", null,
                TaxConceptType.FINE, "M7")));

        UUID sourceEventId = UUID.randomUUID();
        UUID infractionId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.now().minusMinutes(2);
        var data = new ConfirmedInboundEvents.M7InfractionData(infractionId, dni,
            ConfirmedInboundEvents.M7DebtorIdType.DNI, "P4-TEST", "SYNTHETIC_TEST", occurredAt,
            new BigDecimal("100.00"), List.of(), new BigDecimal("100.00"), "P4-TEST-INSPECTOR",
            new ConfirmedInboundEvents.M7Location("Calle Sintética 1", "Esquina Test",
                BigDecimal.valueOf(-34.6), BigDecimal.valueOf(-58.4)));
        var event = new ConfirmedInboundEvents.M7InfractionConfirmedEvent(sourceEventId, "infractionConfirmed",
            "1.0", occurredAt, "transito", correlationId, null, data);
        ExternalObligation obligation = consumer.consume(event);
        Debt debt = debts.findAll().stream().filter(row -> obligation.id.equals(row.externalObligationId))
            .findFirst().orElseThrow(() -> new IllegalStateException("El consumidor M7 no creó la deuda"));
        if (!concept.id.equals(debt.taxConceptId) || !taxpayer.id.equals(debt.taxpayerId))
            throw new IllegalStateException("La obligación M7 no quedó asociada a los fixtures sintéticos");

        IntegrationEventLog source = logs.findFirstByEventIdOrderByIdDesc(sourceEventId).orElseThrow();
        String originalPayload = source.payload;
        JsonNode originalTree = json.readTree(originalPayload);
        if (!correlationId.toString().equals(originalTree.path("correlationId").asText()))
            throw new IllegalStateException("No se conservó la correlación original del evento M7");
        // Falla reversible exclusiva de la PostgreSQL efímera: el evento real ya fue consumido y se restaura el payload original.
        ObjectNode hiddenEvidence = (ObjectNode) originalTree.deepCopy();
        hiddenEvidence.remove("correlationId");
        source.payload = json.writeValueAsString(hiddenEvidence);
        logs.save(source);

        authenticate();
        Payment payment = payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id, PaymentMethod.CASH,
            new BigDecimal("40.00"), List.of(new ApiDtos.AllocationRequest(debt.id, new BigDecimal("40.00")))),
            "P4-" + sourceEventId);
        Debt paidDebt = debts.findById(debt.id).orElseThrow();
        PaymentAllocation allocation = allocations.findByPaymentId(payment.id).stream().findFirst().orElseThrow();
        IntegrationEventLog deferred = logs.findFirstByBusinessKeyOrderByIdDesc(
            "outbound-deferred:paymentRegistered:PaymentAllocation:" + allocation.id + ":Debt:" + debt.id).orElseThrow();
        JsonNode envelope = json.readTree(deferred.payload);
        JsonNode snapshot = envelope.path("snapshot");
        if (!"DEFERRED".equals(envelope.path("phase").asText())
            || !"M7_ORIGINAL_CORRELATION_ID_UNAVAILABLE".equals(deferred.errorMessage))
            throw new IllegalStateException("El pago M7 no quedó aplazado por falta de evidencia original");
        if (payment.status != PaymentStatus.CONFIRMED || paymentRepository.findById(payment.id).isEmpty())
            throw new IllegalStateException("El pago no quedó confirmado y persistido");
        if (outbox.existsByIdempotencyKey(snapshot.path("idempotencyKey").asText()))
            throw new IllegalStateException("Se encoló un evento económico antes de recuperar la correlación");
        if (paidDebt.outstandingBalance.compareTo(new BigDecimal("60.00")) != 0)
            throw new IllegalStateException("El saldo no coincide con la imputación sintética");

        return json.valueToTree(Map.ofEntries(
            Map.entry("databaseVersion", version), Map.entry("databaseHost", address.getHost()),
            Map.entry("databasePort", address.getPort()), Map.entry("databaseName", address.getPath().substring(1)),
            Map.entry("sourceEventId", sourceEventId), Map.entry("correlationId", correlationId),
            Map.entry("infractionId", infractionId), Map.entry("originId", obligation.externalReferenceId),
            Map.entry("debtId", paidDebt.id), Map.entry("debtUuid", paidDebt.integrationId),
            Map.entry("paymentId", payment.id), Map.entry("paymentUuid", payment.integrationId),
            Map.entry("allocationId", allocation.id), Map.entry("paymentStatus", payment.status),
            Map.entry("deferredStatus", deferred.status), Map.entry("deferredReason", deferred.errorMessage),
            Map.entry("deferralId", deferred.id), Map.entry("economicEventId", snapshot.path("eventId").asText()),
            Map.entry("amountApplied", snapshot.path("data").path("amountApplied").decimalValue()),
            Map.entry("historicalBalance", snapshot.path("data").path("remainingBalance").decimalValue()),
            Map.entry("paidAt", snapshot.path("data").path("paidAt").asText()),
            Map.entry("idempotencyKey", snapshot.path("idempotencyKey").asText()),
            Map.entry("snapshotJson", snapshot), Map.entry("snapshotHash", hash(json.writeValueAsBytes(snapshot))),
            Map.entry("originalPayload", originalPayload), Map.entry("outboxEconomicEvents", 0),
            Map.entry("paymentsCount", paymentRepository.count()), Map.entry("debtsCount", debts.count()),
            Map.entry("allocationsCount", allocations.count()), Map.entry("containerVersion", version),
            Map.entry("migrationVersion", jdbc.queryForObject("select max(version) from flyway_schema_history where success=true", Integer.class))));
    }

    private static JsonNode reconcile(ConfigurableApplicationContext context, ObjectMapper json, JdbcTemplate jdbc,
            String version, URI address) throws Exception {
        DebtRepository debts = context.getBean(DebtRepository.class);
        PaymentRepository payments = context.getBean(PaymentRepository.class);
        PaymentAllocationRepository allocations = context.getBean(PaymentAllocationRepository.class);
        IntegrationEventLogRepository logs = context.getBean(IntegrationEventLogRepository.class);
        ExternalObligationRepository obligations = context.getBean(ExternalObligationRepository.class);
        OutboxRepository outbox = context.getBean(OutboxRepository.class);
        DeferredEconomicReconciliation reconciliation = context.getBean(DeferredEconomicReconciliation.class);

        long debtId = Long.parseLong(required("P4_DEBT_ID"));
        long paymentId = Long.parseLong(required("P4_PAYMENT_ID"));
        long allocationId = Long.parseLong(required("P4_ALLOCATION_ID"));
        long deferralId = Long.parseLong(required("P4_DEFERRAL_ID"));
        UUID sourceEventId = UUID.fromString(required("P4_EVENT_ID"));
        Debt debt = debts.findById(debtId).orElseThrow();
        Payment payment = payments.findById(paymentId).orElseThrow();
        PaymentAllocation allocation = allocations.findById(allocationId).orElseThrow();
        IntegrationEventLog deferred = logs.findById(deferralId).orElseThrow();
        IntegrationEventLog source = logs.findFirstByEventIdOrderByIdDesc(sourceEventId).orElseThrow();
        JsonNode hiddenEvidence = json.readTree(source.payload);
        JsonNode deferredBefore = json.readTree(deferred.payload);
        JsonNode snapshotBefore = deferredBefore.path("snapshot");
        long paymentsBefore = payments.count();
        long debtsBefore = debts.count();
        long allocationsBefore = allocations.count();
        if (!"DEFERRED".equals(deferredBefore.path("phase").asText())
            || !sourceEventId.toString().equals(hiddenEvidence.path("eventId").asText())
            || !"infractionConfirmed".equals(hiddenEvidence.path("eventType").asText())
            || source.payload.contains("correlationId"))
            throw new IllegalStateException("El movimiento M7 original no conserva su identidad con la correlación ausente");
        if (!required("P4_EXPECTED_SNAPSHOT_HASH").equals(hash(json.writeValueAsBytes(snapshotBefore))))
            throw new IllegalStateException("La instantánea cambió durante el reinicio");

        DeferredEconomicReconciliation.Result withoutEvidence = reconciliation.reconcile(deferralId);
        if (!"STILL_DEFERRED".equals(withoutEvidence.outcome()))
            throw new IllegalStateException("La conciliación sin evidencia no permaneció aplazada");

        source.payload = required("P4_ORIGINAL_PAYLOAD");
        logs.save(source);
        DeferredEconomicReconciliation.Result prepared = reconciliation.reconcile(deferralId);
        OutboxEvent event = outbox.findByIdempotencyKey(snapshotBefore.path("idempotencyKey").asText()).orElseThrow();
        JsonNode eventPayload = json.readTree(event.payload);
        JsonNode snapshotAfter = json.readTree(deferred.payload).path("snapshot");
        DeferredEconomicReconciliation.Result repeated = reconciliation.reconcile(deferralId);

        String expectedCorrelation = json.readTree(required("P4_ORIGINAL_PAYLOAD")).path("correlationId").asText();
        if (!"PREPARED_IN_OUTBOX".equals(prepared.outcome()) || !"ALREADY_PREPARED".equals(repeated.outcome()))
            throw new IllegalStateException("La conciliación no fue idempotente");
        if (!event.id.toString().equals(snapshotBefore.path("eventId").asText())
            || !event.id.equals(repeated.outboxEventId())
            || !expectedCorrelation.equals(eventPayload.path("correlationId").asText()))
            throw new IllegalStateException("El outbox no conserva EventId/correlación originales");
        if (!snapshotBefore.equals(snapshotAfter)
            || !required("P4_EXPECTED_SNAPSHOT_HASH").equals(hash(json.writeValueAsBytes(snapshotAfter))))
            throw new IllegalStateException("La conciliación alteró la instantánea histórica");
        if (payments.count() != paymentsBefore || debts.count() != debtsBefore || allocations.count() != allocationsBefore)
            throw new IllegalStateException("La conciliación duplicó movimientos económicos");
        if (payment.status != PaymentStatus.CONFIRMED || allocation.status.equals("REVERSED")
            || debt.outstandingBalance.compareTo(new BigDecimal("60.00")) != 0)
            throw new IllegalStateException("La conciliación modificó el estado económico ya ejecutado");
        if (obligations.findBySourceModuleAndExternalTypeAndExternalReferenceId("M7",
                ExternalObligationType.TRAFFIC_INFRACTION, debtOriginId(debt, obligations)).isEmpty())
            throw new IllegalStateException("No se conserva la obligación M7 de origen");

        return json.valueToTree(Map.ofEntries(
            Map.entry("databaseVersion", version), Map.entry("databaseHost", address.getHost()),
            Map.entry("databasePort", address.getPort()), Map.entry("databaseName", address.getPath().substring(1)),
            Map.entry("sourceEventId", sourceEventId), Map.entry("correlationId", expectedCorrelation),
            Map.entry("originId", snapshotAfter.path("originId").asText()),
            Map.entry("debtId", debt.id), Map.entry("debtUuid", debt.integrationId),
            Map.entry("paymentId", payment.id), Map.entry("paymentUuid", payment.integrationId),
            Map.entry("economicEventId", event.id), Map.entry("outcomeWithoutEvidence", withoutEvidence.outcome()),
            Map.entry("outcomeAfterRestore", prepared.outcome()), Map.entry("retryOutcome", repeated.outcome()),
            Map.entry("amountApplied", snapshotAfter.path("data").path("amountApplied").decimalValue()),
            Map.entry("historicalBalance", snapshotAfter.path("data").path("remainingBalance").decimalValue()),
            Map.entry("snapshotHash", hash(json.writeValueAsBytes(snapshotAfter))),
            Map.entry("paymentsCount", payments.count()), Map.entry("debtsCount", debts.count()),
            Map.entry("allocationsCount", allocations.count()), Map.entry("outboxEventsForMovement",
                outbox.findAll().stream().filter(row -> event.id.equals(row.id)).count()),
            Map.entry("outboxStatus", event.status), Map.entry("deliveryStatus", prepared.deliveryStatus()),
            Map.entry("migrationVersion", jdbc.queryForObject("select max(version) from flyway_schema_history where success=true", Integer.class))));
    }

    private static String debtOriginId(Debt debt, ExternalObligationRepository obligations) {
        return obligations.findById(debt.externalObligationId).orElseThrow().externalReferenceId;
    }

    private static URI isolatedAddress(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("P4 requiere JDBC PostgreSQL");
        URI address = URI.create(jdbcUrl.substring("jdbc:".length()));
        String database = address.getPath() == null ? "" : address.getPath().replaceFirst("^/", "");
        if (address.getPort() == 5432 || !"test".equals(database) || "rentas".equalsIgnoreCase(database))
            throw new IllegalArgumentException("Se aborta: el datasource no parece PostgreSQL efímero de Testcontainers");
        return address;
    }

    private static void authenticate() {
        var identity = new AuthenticatedIdentity("p4-physical-test", null);
        var authentication = new UsernamePasswordAuthenticationToken(identity, null,
            List.of(new SimpleGrantedAuthority("ROLE_CASHIER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static String hash(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Falta configuración de prueba " + name);
        return value;
    }

    @Configuration(proxyBeanMethods = false)
    static class DisabledSchedulingConfiguration {
        @Bean TaskScheduler taskScheduler() {
            return mock(TaskScheduler.class, invocation -> ScheduledFuture.class.isAssignableFrom(
                invocation.getMethod().getReturnType()) ? mock(ScheduledFuture.class) : RETURNS_DEFAULTS.answer(invocation));
        }
    }
}
