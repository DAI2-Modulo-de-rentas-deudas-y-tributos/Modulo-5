package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

@Component
@ConditionalOnProperty(name="BROKER_ADAPTER",havingValue="core-http")
class CoreHttpEventPublisher implements EventPublisher {
    private final ObjectMapper json;
    private final CoreModuleTokenService moduleClient;
    private final Duration requestTimeout;
    private final HttpClient client;

    CoreHttpEventPublisher(ObjectMapper json,String baseUrl,String moduleSecret,Duration connectTimeout,
        Duration requestTimeout,Duration tokenSkew,java.time.Clock clock) {
        this(json,new CoreModuleTokenService(json,baseUrl,moduleSecret,connectTimeout,requestTimeout,tokenSkew,clock));
    }

    @Autowired
    CoreHttpEventPublisher(ObjectMapper json,CoreModuleTokenService moduleClient) {
        this.json=json;this.moduleClient=moduleClient;
        moduleClient.validateConfiguration();
        this.requestTimeout=moduleClient.requestTimeout();
        this.client=moduleClient.httpClient();
    }

    CoreHttpEventPublisher(ObjectMapper json,String baseUrl,String moduleSecret,Duration connectTimeout,
        Duration requestTimeout,Duration tokenSkew) {
        this(json,baseUrl,moduleSecret,connectTimeout,requestTimeout,tokenSkew,java.time.Clock.systemUTC());
    }

    @Override public OutboxStatus publish(String eventType,String targetModule,String payload) {
        if(!"CORE".equals(targetModule))throw permanent("CORE_HTTP_UNSUPPORTED_OUTBOX_TARGET");
        UUID eventId=validateEnvelope(eventType,payload);
        CoreModuleTokenService.AccessToken token=moduleClient.accessToken();
        HttpResponse<String> response=sendEvent(eventId,payload,token);
        if(response.statusCode()==401){
            moduleClient.invalidate(token);
            token=moduleClient.accessToken();
            response=sendEvent(eventId,payload,token);
            if(response.statusCode()==401)throw permanent("CORE_HTTP_EVENT_AUTHORIZATION_401");
        }
        return accepted(response,eventId)?OutboxStatus.CORE_ACCEPTED:throwUnexpected(response.statusCode());
    }

    private HttpResponse<String> sendEvent(UUID eventId,String payload,CoreModuleTokenService.AccessToken token) {
        HttpRequest request=HttpRequest.newBuilder(moduleClient.endpoint("/api/v1/events"))
            .timeout(requestTimeout).header("Authorization","Bearer "+token.value())
            .header("Content-Type","application/json").header("Accept","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<String> response=send(request,"CORE_HTTP_EVENT_TRANSPORT_FAILED");
        if(response.statusCode()==401)return response;
        if(response.statusCode()==403)
            throw permanent("CORE_HTTP_EVENT_AUTHORIZATION_"+response.statusCode());
        if(response.statusCode()==422)throw permanent("CORE_HTTP_EVENT_VALIDATION_422");
        if(response.statusCode()==408||response.statusCode()==429||response.statusCode()>=500)
            throw retryable("CORE_HTTP_EVENT_RETRYABLE_"+response.statusCode());
        if(response.statusCode()!=200&&response.statusCode()!=202)
            throw permanent("CORE_HTTP_EVENT_REJECTED_"+response.statusCode());
        return response;
    }

    private boolean accepted(HttpResponse<String> response,UUID eventId) {
        try {
            if(response.statusCode()==200&&(response.body()==null||response.body().isBlank()))return true;
            JsonNode body=json.readTree(response.body());
            if(body.hasNonNull("eventId")&&!eventId.equals(UUID.fromString(body.path("eventId").asText())))
                throw permanent("CORE_HTTP_RESPONSE_EVENT_ID_MISMATCH");
            if(response.statusCode()==200)return body.path("duplicate").asBoolean(false);
            return body.path("accepted").asBoolean(false)&&eventId.equals(UUID.fromString(body.path("eventId").asText()));
        } catch(CoreHttpPublishException ex){throw ex;}
        catch(Exception ex){throw retryable("CORE_HTTP_RESPONSE_INVALID");}
    }

    private UUID validateEnvelope(String eventType,String payload) {
        try {
            JsonNode envelope=json.readTree(payload);
            UUID eventId=UUID.fromString(envelope.path("eventId").asText());
            if(!eventType.equals(envelope.path("eventType").asText())
                ||!"1.0".equals(envelope.path("eventVersion").asText())
                ||!"rentas".equals(envelope.path("sourceModule").asText())
                ||!envelope.path("data").isObject())throw new IllegalArgumentException();
            OffsetDateTime.parse(envelope.path("occurredAt").asText());
            uuidIfPresent(envelope,"correlationId");uuidIfPresent(envelope,"causationId");
            return eventId;
        } catch(Exception ex){throw permanent("CORE_HTTP_INVALID_OUTBOX_ENVELOPE");}
    }

    private void uuidIfPresent(JsonNode envelope,String field) {
        if(envelope.hasNonNull(field))UUID.fromString(envelope.path(field).asText());
    }

    private HttpResponse<String> send(HttpRequest request,String transportCode) {
        try{return client.send(request,HttpResponse.BodyHandlers.ofString());}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw retryable(transportCode);}
        catch(IOException|RuntimeException ex){throw retryable(transportCode);}
    }

    private static OutboxStatus throwUnexpected(int status){throw permanent("CORE_HTTP_UNEXPECTED_RESPONSE_"+status);}
    private static CoreHttpPublishException permanent(String code){return new CoreHttpPublishException(code,false);}
    private static CoreHttpPublishException retryable(String code){return new CoreHttpPublishException(code,true);}
}

final class CoreHttpPublishException extends RuntimeException {
    private final boolean retryable;
    CoreHttpPublishException(String code,boolean retryable){super(code);this.retryable=retryable;}
    String code(){return getMessage();}
    boolean retryable(){return retryable;}
}
