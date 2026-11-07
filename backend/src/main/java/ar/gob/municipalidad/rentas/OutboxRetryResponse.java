package ar.gob.municipalidad.rentas;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OutboxRetryResponse(UUID id, String eventType, String targetModule,
                                  OutboxStatus status, int retryCount, OffsetDateTime queuedAt) {}
