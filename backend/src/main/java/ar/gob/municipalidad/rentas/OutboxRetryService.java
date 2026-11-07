package ar.gob.municipalidad.rentas;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
class OutboxRetryService {
    private final EntityManager entities;
    private final AuditService audit;

    OutboxRetryService(EntityManager entities, AuditService audit) {
        this.entities = entities;
        this.audit = audit;
    }

    @Transactional
    OutboxRetryResponse requeue(UUID id) {
        OutboxEvent event = entities.find(OutboxEvent.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (event == null) throw CatalogService.notFound("Evento Outbox");
        CatalogService.requireConflict(event.status == OutboxStatus.FAILED || event.status == OutboxStatus.DEAD_LETTER,
            "OUTBOX_EVENT_NOT_RETRYABLE", "Solo se pueden reenviar eventos fallidos o en DLQ");
        CatalogService.requireConflict(!"PLATFORM".equals(event.targetModule),
            "OUTBOX_EVENT_NOT_RETRYABLE", "Los eventos internos no se pueden reenviar");

        OutboxStatus previousStatus = event.status;
        int previousRetryCount = event.retryCount;
        event.status = OutboxStatus.PENDING;
        event.retryCount = 0;
        OffsetDateTime queuedAt = OffsetDateTime.now();
        audit.record("OutboxEvent", event.id, "OUTBOX_MANUAL_RETRY_QUEUED",
            Map.of("status", previousStatus.name(), "retryCount", previousRetryCount),
            Map.of("status", event.status.name(), "eventType", event.eventType, "targetModule", event.targetModule));
        return new OutboxRetryResponse(event.id, event.eventType, event.targetModule, event.status, event.retryCount, queuedAt);
    }
}
