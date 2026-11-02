package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/** Solo consulta registros existentes; nunca concilia ni persiste clasificaciones. */
final class HistoricalMovementDiagnostics {
    enum Classification {
        VERIFIED, DEFERRED, LEGACY_IDS_REVIEW_REQUIRED, HISTORICAL_SNAPSHOT_UNAVAILABLE,
        UNSAFE_REVERSAL_BLOCKED, MANUAL_REVIEW_REQUIRED, LOCAL_OUTBOX_PREPARED_CORE_UNCONFIRMED
    }

    record Finding(String movement, Long referenceId, Classification classification, String reason,
        boolean safeToReconcile, boolean manualReview, boolean externalDependency) {}

    private final IntegrationEventLogRepository logs;
    private final OutboxRepository outbox;
    private final PaymentAllocationRepository allocations;
    private final InstallmentRepository installments;
    private final PaymentPlanRepository plans;
    private final PaymentPlanDebtRepository planDebts;
    private final PaymentPlanPrincipalLedger principalLedger;
    private final ObjectMapper json;

    HistoricalMovementDiagnostics(IntegrationEventLogRepository logs, OutboxRepository outbox,
            PaymentAllocationRepository allocations, InstallmentRepository installments,
            PaymentPlanRepository plans, PaymentPlanDebtRepository planDebts,
            PaymentPlanPrincipalLedger principalLedger, ObjectMapper json) {
        this.logs = logs;
        this.outbox = outbox;
        this.allocations = allocations;
        this.installments = installments;
        this.plans = plans;
        this.planDebts = planDebts;
        this.principalLedger = principalLedger;
        this.json = json;
    }

    Finding deferredEconomic(Long logId) {
        IntegrationEventLog log = logs.findById(logId).orElseThrow();
        JsonNode root = read(log.payload);
        JsonNode snapshot = root.path("snapshot");
        if (snapshot.isMissingNode() || snapshot.isNull())
            return finding(log, Classification.HISTORICAL_SNAPSHOT_UNAVAILABLE,
                "No hay instantánea económica; requiere revisión manual", false, true, true);
        JsonNode data = snapshot.path("data");
        if (data.path("debtId").asText().startsWith("debt-")
                || data.path("paymentId").asText().startsWith("payment-"))
            return finding(log, Classification.LEGACY_IDS_REVIEW_REQUIRED,
                "Contiene identificadores económicos legados; no convertir automáticamente", false, true, true);
        if ("OUTBOX_PREPARED".equals(root.path("phase").asText())) {
            try {
                UUID outboxId = UUID.fromString(root.path("outboxEventId").asText());
                if (outbox.findById(outboxId).isPresent())
                    return finding(log, Classification.LOCAL_OUTBOX_PREPARED_CORE_UNCONFIRMED,
                        "Existe outbox local; no acredita entrega ni aceptación de Core", false, false, true);
            } catch (RuntimeException ignored) {
                // La evidencia incompleta queda en revisión; el diagnóstico no intenta repararla.
            }
            return finding(log, Classification.MANUAL_REVIEW_REQUIRED,
                "El registro preparado no tiene un outbox verificable", false, true, true);
        }
        if (!"DEFERRED".equals(root.path("phase").asText()))
            return finding(log, Classification.MANUAL_REVIEW_REQUIRED,
                "Fase económica desconocida", false, true, true);
        try {
            UUID sourceEventId = UUID.fromString(snapshot.path("sourceEventId").asText());
            IntegrationEventLog source = logs.findFirstByEventIdOrderByIdDesc(sourceEventId).orElse(null);
            if (source == null || read(source.payload).path("correlationId").asText().isBlank())
                return finding(log, Classification.DEFERRED,
                    "Falta evidencia original de correlación; no se ejecutó conciliación", false, false, true);
        } catch (RuntimeException ignored) {
            return finding(log, Classification.MANUAL_REVIEW_REQUIRED,
                "No se puede verificar la evidencia del evento de origen", false, true, true);
        }
        return finding(log, Classification.DEFERRED,
            "Instantánea y correlación existen; la conciliación real debe validar el dominio", false, false, true);
    }

    Finding installmentAllocation(Long allocationId) {
        PaymentAllocation allocation = allocations.findById(allocationId).orElseThrow();
        if (allocation.targetType != AllocationTargetType.INSTALLMENT || allocation.installmentId == null)
            return new Finding("payment-allocation", allocationId, Classification.VERIFIED,
                "No es una imputación de cuota", false, false, false);
        Installment installment = installments.findById(allocation.installmentId).orElseThrow();
        if (installment.status == InstallmentStatus.CANCELLED)
            return new Finding("installment-allocation", allocationId, Classification.UNSAFE_REVERSAL_BLOCKED,
                "Cuota CANCELLED con imputación histórica activa; no modificar", false, true, false);
        Long planId = installments.findPaymentPlanIdById(installment.id).orElseThrow();
        PaymentPlan plan = plans.findById(planId).orElseThrow();
        List<PaymentPlanDebt> links = planDebts.findByPaymentPlanId(plan.id);
        try {
            if (principalLedger.find(allocation).isEmpty() && allocation.principalApplied.signum() > 0
                    && links.size() > 1)
                return new Finding("installment-allocation", allocationId, Classification.MANUAL_REVIEW_REQUIRED,
                    "Falta distribución histórica exacta entre varias deudas", false, true, false);
        } catch (RuntimeException ignored) {
            return new Finding("installment-allocation", allocationId, Classification.MANUAL_REVIEW_REQUIRED,
                "La evidencia de distribución no puede validarse", false, true, false);
        }
        return new Finding("installment-allocation", allocationId, Classification.VERIFIED,
            "La evidencia de distribución es consistente; el diagnóstico no ejecuta reversión", false, false, false);
    }

    private Finding finding(IntegrationEventLog log, Classification classification, String reason,
            boolean safeToReconcile, boolean manualReview, boolean externalDependency) {
        return new Finding(log.eventType, log.id, classification, reason,
            safeToReconcile, manualReview, externalDependency);
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (Exception ignored) { return json.createObjectNode(); }
    }
}
