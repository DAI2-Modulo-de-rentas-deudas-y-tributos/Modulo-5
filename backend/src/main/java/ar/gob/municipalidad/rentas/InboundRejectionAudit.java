package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
class InboundRejectionAudit {
    private static final Pattern IDENTIFIER=Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,63}");
    private static final Pattern VERSION=Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}");
    private final IntegrationEventLogRepository logs;
    private final ObjectMapper json;

    InboundRejectionAudit(IntegrationEventLogRepository logs,ObjectMapper json){this.logs=logs;this.json=json;}

    boolean alreadyRecorded(String raw){
        JsonNode envelope=read(raw);
        String externalId=uuid(text(envelope,"eventId")).map(UUID::toString).orElse("rejected:"+sha256(raw));
        return logs.existsByBusinessKey("INBOUND_REJECTION:"+externalId);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    void record(String raw,RuntimeException failure){
        JsonNode envelope=read(raw);
        String digest=sha256(raw);
        String externalId=uuid(text(envelope,"eventId")).map(UUID::toString).orElse("rejected:"+digest);
        String businessKey="INBOUND_REJECTION:"+externalId;
        // Keep the audit row's internal key separate from the producer's event id.
        UUID eventId=IntegrationInbox.technicalId(businessKey);
        OffsetDateTime receivedAt=OffsetDateTime.now();
        OffsetDateTime occurredAt=offset(text(envelope,"occurredAt")).orElse(receivedAt);
        String eventType=identifier(text(envelope,"eventType"),"UNKNOWN");
        String sourceModule=identifier(text(envelope,"sourceModule"),"UNKNOWN");
        logs.insertRejectedInbound(eventId,externalId,businessKey,eventType,sourceModule,
            safeMetadata(envelope,eventId,eventType,sourceModule),OperationalLogSupport.safeFailureReason(failure),
            occurredAt,receivedAt);
    }

    private String safeMetadata(JsonNode envelope,UUID eventId,String eventType,String sourceModule){
        var safe=json.createObjectNode().put("eventId",uuid(text(envelope,"eventId")).map(UUID::toString).orElse(eventId.toString()))
            .put("eventType",eventType).put("sourceModule",sourceModule);
        string(envelope,"eventVersion").filter(VERSION.asMatchPredicate()).ifPresent(value->safe.put("eventVersion",value));
        uuid(text(envelope,"correlationId")).ifPresent(value->safe.put("correlationId",value.toString()));
        uuid(text(envelope,"causationId")).ifPresent(value->safe.put("causationId",value.toString()));
        offset(text(envelope,"occurredAt")).ifPresent(value->safe.put("occurredAt",value.toString()));
        try{return json.writeValueAsString(safe);}catch(Exception ignored){return "{}";}
    }

    private JsonNode read(String raw){try{return json.readTree(raw);}catch(Exception ignored){return json.createObjectNode();}}
    private String text(JsonNode node,String field){JsonNode value=node.path(field);return value.isTextual()?value.asText():null;}
    private java.util.Optional<String> string(JsonNode node,String field){return java.util.Optional.ofNullable(text(node,field));}
    private String identifier(String value,String fallback){return value!=null&&IDENTIFIER.matcher(value).matches()?value:fallback;}
    private java.util.Optional<UUID> uuid(String value){try{return value==null?java.util.Optional.empty():java.util.Optional.of(UUID.fromString(value));}catch(IllegalArgumentException ignored){return java.util.Optional.empty();}}
    private java.util.Optional<OffsetDateTime> offset(String value){try{return value==null?java.util.Optional.empty():java.util.Optional.of(OffsetDateTime.parse(value));}catch(RuntimeException ignored){return java.util.Optional.empty();}}
    private String sha256(String raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException("SHA-256 unavailable",ex);}}
}
