package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

record NormalizedIntegrationEvent(String eventId,String eventType,OffsetDateTime occurredAt,
    String sourceModule,String subject,JsonNode payload,String correlationId) {}
