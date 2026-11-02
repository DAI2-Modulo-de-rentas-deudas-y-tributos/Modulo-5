package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
class OutboxEventosDeDominio implements EventosDeDominio {
    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final ConfirmedIntegrationOutbox integracion;

    OutboxEventosDeDominio(OutboxRepository outbox, ObjectMapper json, ConfirmedIntegrationOutbox integracion) {
        this.outbox = outbox;
        this.json = json;
        this.integracion = integracion;
    }

    @Override public void deudaSaldada(Debt deuda) { integracion.debtSettled(deuda); }
    @Override public void deudaSaldada(Debt deuda, PaymentAllocation imputacion) { integracion.debtSettled(deuda, imputacion); }

    @Override public void deudaAjustada(Debt deuda, AdjustmentRequest ajuste) {
        guardar("debtAdjusted", "Debt", deuda.id, new DeudaAjustada(deuda.id, ajuste.id));
    }

    @Override public void planCaducado(PaymentPlan plan) {
        guardar("paymentPlanExpired", "PaymentPlan", plan.id, new PlanId(plan.id));
    }

    @Override public void planRefinanciado(PaymentPlan anterior, PaymentPlan nuevo) {
        guardar("paymentPlanRefinanced", "PaymentPlan", nuevo.id,
            new PlanesRefinanciados(anterior.id, nuevo.id));
    }

    @Override public void pagoRegistrado(Payment pago) {
        guardar("paymentRegistered", "Payment", pago.id,
            new PagoRegistrado(pago.id, pago.taxpayerId, pago.amount));
    }

    @Override public void planCompletado(PaymentPlan plan) {
        guardar("paymentPlanCompleted", "PaymentPlan", plan.id, new PlanId(plan.id));
    }

    private void guardar(String tipo, String agregado, Long agregadoId, Object datos) {
        OutboxEvent evento = new OutboxEvent();
        evento.id = UUID.randomUUID();
        evento.eventType = tipo;
        evento.targetModule = "PLATFORM";
        evento.aggregateType = agregado;
        evento.aggregateId = String.valueOf(agregadoId);
        try {
            evento.payload = json.writeValueAsString(datos);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("INVALID_EVENT_PAYLOAD", "No se pudo serializar el evento saliente", 422);
        }
        evento.status = OutboxStatus.PENDING;
        evento.retryCount = 0;
        evento.createdAt = OffsetDateTime.now();
        outbox.save(evento);
    }

    private record DeudaAjustada(Long debtId, Long adjustmentId) {}
    private record PlanId(Long paymentPlanId) {}
    private record PlanesRefinanciados(Long oldPaymentPlanId, Long newPaymentPlanId) {}
    private record PagoRegistrado(Long paymentId, Long taxpayerId, java.math.BigDecimal amount) {}
}
