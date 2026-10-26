package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("postgres-it")
@Testcontainers
class HistoricalMovementAuditPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/migration-postgresql");
    }

    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired IntegrationEventLogRepository logs;
    @Autowired OutboxRepository outbox;
    @Autowired PaymentAllocationRepository allocations;
    @Autowired InstallmentRepository installments;
    @Autowired PaymentPlanRepository plans;
    @Autowired PaymentPlanDebtRepository planDebts;
    @Autowired PaymentPlanPrincipalLedger principalLedger;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired PlanWorkflowService workflow;
    @Autowired PaymentService payments;
    @Autowired ReversalService reversals;
    @Autowired PaymentReversalRepository reversalRequests;
    @Autowired DebtRepository debts;
    @Autowired PaymentRepository paymentRepository;
    @Autowired TaxpayerRepository taxpayers;

    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test void diagnosticClassifiesSyntheticDeferredEvidenceWithoutChangingIt() throws Exception {
        authenticate();
        UUID sourceId = UUID.randomUUID();
        IntegrationEventLog source = log(sourceId, "infractionConfirmed", EventDirection.INBOUND,
            IntegrationEventStatus.PROCESSED, json.writeValueAsString(java.util.Map.of("correlationId", UUID.randomUUID().toString())));
        logs.save(source);
        UUID debtUuid = UUID.randomUUID(), paymentUuid = UUID.randomUUID(), originId = UUID.randomUUID();
        UUID movementEventId = UUID.randomUUID();
        ObjectNode data = json.createObjectNode().put("paymentId", paymentUuid.toString())
            .put("debtId", debtUuid.toString()).put("originType", "TRAFFIC_FINE")
            .put("originId", originId.toString()).put("infractionId", originId.toString())
            .put("amountApplied", 25).put("paidAt", OffsetDateTime.now().toString()).put("remainingBalance", 75);
        ObjectNode snapshot = json.createObjectNode().put("eventType", "paymentRegistered")
            .put("aggregateType", "PaymentAllocation").put("aggregateId", 44).put("debtId", 22)
            .put("paymentId", 33).put("allocationId", 44).put("sourceEventId", sourceId.toString())
            .put("originId", originId.toString()).put("targetModule", "M7")
            .put("idempotencyKey", "outbound:paymentRegistered:PaymentAllocation:44:Debt:22")
            .put("eventId", movementEventId.toString()).put("occurredAt", OffsetDateTime.now().toString())
            .set("data", data);
        IntegrationEventLog marker = log(UUID.randomUUID(), "paymentRegistered", EventDirection.OUTBOUND,
            IntegrationEventStatus.PENDING, json.writeValueAsString(json.createObjectNode()
                .put("phase", "DEFERRED").set("snapshot", snapshot)));
        marker.businessKey = "outbound-deferred:paymentRegistered:PaymentAllocation:44:Debt:22";
        marker.targetModule = "M7";
        logs.save(marker);
        HistoricalMovementDiagnostics diagnostic = diagnostic();
        String original = logs.findById(marker.id).orElseThrow().payload;
        long outboxBefore = outbox.count();

        assertThat(diagnostic.deferredEconomic(marker.id).classification())
            .isEqualTo(HistoricalMovementDiagnostics.Classification.DEFERRED);
        assertThat(logs.findById(marker.id).orElseThrow().payload).isEqualTo(original);

        ObjectNode withoutSnapshot = (ObjectNode) json.readTree(original);
        withoutSnapshot.remove("snapshot");
        marker.payload = json.writeValueAsString(withoutSnapshot);
        logs.save(marker);
        assertThat(diagnostic.deferredEconomic(marker.id).classification())
            .isEqualTo(HistoricalMovementDiagnostics.Classification.HISTORICAL_SNAPSHOT_UNAVAILABLE);

        ObjectNode legacy = (ObjectNode) json.readTree(original);
        ((ObjectNode) legacy.path("snapshot").path("data")).put("paymentId", "payment-33");
        marker.payload = json.writeValueAsString(legacy);
        logs.save(marker);
        assertThat(diagnostic.deferredEconomic(marker.id).classification())
            .isEqualTo(HistoricalMovementDiagnostics.Classification.LEGACY_IDS_REVIEW_REQUIRED);

        UUID preparedId = UUID.randomUUID();
        OutboxEvent prepared = new OutboxEvent();
        prepared.id = preparedId; prepared.eventType = "paymentRegistered"; prepared.targetModule = "CORE";
        prepared.aggregateType = "PaymentAllocation"; prepared.aggregateId = "44"; prepared.payload = "{}";
        prepared.status = OutboxStatus.PENDING; prepared.retryCount = 0; prepared.createdAt = OffsetDateTime.now();
        outbox.save(prepared);
        ObjectNode preparedMarker = json.createObjectNode().put("phase", "OUTBOX_PREPARED")
            .put("outboxEventId", preparedId.toString()).put("deliveryConfirmed", false);
        preparedMarker.set("snapshot", snapshot);
        marker.payload = json.writeValueAsString(preparedMarker);
        logs.save(marker);
        assertThat(diagnostic.deferredEconomic(marker.id).classification())
            .isEqualTo(HistoricalMovementDiagnostics.Classification.LOCAL_OUTBOX_PREPARED_CORE_UNCONFIRMED);
        assertThat(outbox.findById(preparedId).orElseThrow().status).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.count()).isEqualTo(outboxBefore + 1);
    }

    @Test void cancelledInstallmentAndUnknownMultiDebtDistributionBlockReversalWithoutEconomicChanges() {
        authenticate();
        configureTwoInstallments();

        TaxpayerReference cancelledOwner = taxpayer("CANCELLED");
        Debt cancelledDebt = debt("CANCELLED", cancelledOwner.id);
        Long cancelledPlanId = grantPlan(cancelledOwner.id, List.of(cancelledDebt.id));
        Installment cancelledInstallment = installments.findByPaymentPlanIdOrderByNumber(cancelledPlanId).get(0);
        Payment cancelledPayment = payInstallment(cancelledOwner.id, cancelledInstallment);
        PaymentAllocation cancelledAllocation = allocations.findByPaymentId(cancelledPayment.id).get(0);
        removeDistribution(cancelledAllocation.id);
        Installment cancelled = installments.findById(cancelledInstallment.id).orElseThrow();
        cancelled.status = InstallmentStatus.CANCELLED;
        installments.save(cancelled);
        HistoricalMovementDiagnostics diagnostic = diagnostic();
        assertThat(diagnostic.installmentAllocation(cancelledAllocation.id).classification())
            .isEqualTo(HistoricalMovementDiagnostics.Classification.UNSAFE_REVERSAL_BLOCKED);
        assertBlockedReversalUnchanged(cancelledPayment, cancelledAllocation, List.of(cancelledDebt),
            cancelledInstallment, cancelledPlanId, "INSTALLMENT_CANCELLED");

        TaxpayerReference multiOwner = taxpayer("MULTI");
        Debt firstDebt = debt("MULTI-A", multiOwner.id), secondDebt = debt("MULTI-B", multiOwner.id);
        Long multiPlanId = grantPlan(multiOwner.id, List.of(firstDebt.id, secondDebt.id));
        Installment installment = installments.findByPaymentPlanIdOrderByNumber(multiPlanId).get(0);
        Payment payment = payInstallment(multiOwner.id, installment);
        PaymentAllocation allocation = allocations.findByPaymentId(payment.id).get(0);
        removeDistribution(allocation.id);
        assertThat(diagnostic.installmentAllocation(allocation.id).classification())
            .isEqualTo(HistoricalMovementDiagnostics.Classification.MANUAL_REVIEW_REQUIRED);
        assertBlockedReversalUnchanged(payment, allocation, List.of(firstDebt, secondDebt),
            installment, multiPlanId, "HISTORICAL_PLAN_DISTRIBUTION_UNVERIFIABLE");
    }

    private void assertBlockedReversalUnchanged(Payment payment, PaymentAllocation allocation, List<Debt> affected,
            Installment installment, Long planId, String expectedCode) {
        PaymentReversalRequest reversal = reversals.request(payment.id, "Fixture histórica no reversible");
        reversals.approve(reversal.id);
        String before = economicState(payment.id, allocation.id, affected, installment.id, planId);
        long outboxBefore = outbox.count();
        assertThatThrownBy(() -> reversals.execute(reversal.id)).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code).isEqualTo(expectedCode));
        assertThat(economicState(payment.id, allocation.id, affected, installment.id, planId)).isEqualTo(before);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
        assertThat(paymentRepository.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(allocations.findById(allocation.id).orElseThrow().status).isEqualTo("ACTIVE");
        assertThat(reversalRequests.findById(reversal.id).orElseThrow().status).isEqualTo(PaymentReversalStatus.APPROVED);
    }

    private String economicState(Long paymentId, Long allocationId, List<Debt> affected, Long installmentId, Long planId) {
        return paymentRepository.findById(paymentId).orElseThrow().status + "|"
            + allocations.findById(allocationId).orElseThrow().status + "|"
            + affected.stream().map(debt -> debts.findById(debt.id).orElseThrow().outstandingBalance.toPlainString()).toList()
            + "|" + installments.findById(installmentId).map(i -> i.status + ":" + i.paidAmount + ":" + i.outstandingAmount).orElseThrow()
            + "|" + plans.findById(planId).map(p -> p.status + ":" + p.paidAmount + ":" + p.outstandingPlanAmount).orElseThrow();
    }

    private HistoricalMovementDiagnostics diagnostic() {
        return new HistoricalMovementDiagnostics(logs, outbox, allocations, installments, plans, planDebts,
            principalLedger, json);
    }

    private IntegrationEventLog log(UUID id, String type, EventDirection direction, IntegrationEventStatus status, String payload) {
        IntegrationEventLog log = new IntegrationEventLog();
        log.eventId = id; log.externalEventId = id.toString(); log.eventType = type; log.sourceModule = "transito";
        log.direction = direction; log.status = status; log.payload = payload; log.retryCount = 0;
        log.occurredAt = OffsetDateTime.now(); log.receivedAt = OffsetDateTime.now();
        return log;
    }

    private TaxpayerReference taxpayer(String suffix) {
        return catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,
            "HISTORY-" + suffix + "-" + UUID.randomUUID(), UUID.randomUUID().toString().replace("-", "").substring(0, 8),
            null, "Fixture P4 " + suffix));
    }

    private Debt debt(String suffix, Long taxpayerId) {
        TaxConcept concept = catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("HISTORY_" + suffix + "_" + UUID.randomUUID(),
            "Fixture P4 " + suffix, null, TaxConceptType.FEE, "M5"));
        TaxConfiguration configuration = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,
            CalculationType.FIXED, null, new BigDecimal("100"), null, null, true, true, LocalDate.now().minusDays(1), null));
        catalog.submit(configuration.id); catalog.approve(configuration.id);
        Liquidation liquidation = liquidations.create(new ApiDtos.LiquidationRequest(taxpayerId, concept.id,
            YearMonth.now().toString(), BigDecimal.ZERO, LocalDate.now().plusDays(30)));
        return debts.findByLiquidationId(liquidation.id).orElseThrow();
    }

    private void configureTwoInstallments() {
        workflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(2, 2, BigDecimal.ZERO,
            new BigDecimal("20"), 0, 1, true, true, 1, LocalDate.now().minusDays(1), null, true));
    }

    private Long grantPlan(Long taxpayerId, List<Long> debtIds) {
        PaymentPlanRequest request = workflow.request(new ApiDtos.CreatePaymentPlanRequest(taxpayerId, debtIds, 2));
        return workflow.grant(request.id, null).paymentPlanId;
    }

    private Payment payInstallment(Long taxpayerId, Installment installment) {
        return payments.register(new ApiDtos.RegisterPaymentRequest(taxpayerId, PaymentMethod.CASH, installment.totalAmount,
            List.of(new ApiDtos.AllocationRequest(null, installment.id, installment.totalAmount))));
    }

    private void removeDistribution(Long allocationId) {
        jdbc.update("delete from audit_entry where entity_type='PaymentAllocation' and entity_id=? and action='PLAN_PRINCIPAL_DISTRIBUTED'",
            allocationId.toString());
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("historical-audit-test", null), null,
            List.of(new SimpleGrantedAuthority("ROLE_RENTAS"), new SimpleGrantedAuthority("ROLE_SUPERVISOR"),
                new SimpleGrantedAuthority("ROLE_CASHIER"))));
    }
}
