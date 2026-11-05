package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Component
@ConditionalOnProperty(name="BROKER_ADAPTER",havingValue="core-http")
class CoreHttpEventPublisher implements EventPublisher {
    private final ObjectMapper json;
    private final URI baseUri;
    private final String moduleSecret;
    private final Duration requestTimeout,tokenSkew;
    private final Clock clock;
    private final HttpClient client;
    private Token cachedToken;

    @org.springframework.beans.factory.annotation.Autowired
    CoreHttpEventPublisher(ObjectMapper json,
        @Value("${rentas.core.http.base-url:}") String baseUrl,
        @Value("${rentas.core.http.module-secret:}") String moduleSecret,
        @Value("${rentas.core.http.connect-timeout:3s}") Duration connectTimeout,
        @Value("${rentas.core.http.request-timeout:10s}") Duration requestTimeout,
        @Value("${rentas.core.http.token-expiry-skew:PT30S}") Duration tokenSkew) {
        this(json,baseUrl,moduleSecret,connectTimeout,requestTimeout,tokenSkew,Clock.systemUTC());
    }

    CoreHttpEventPublisher(ObjectMapper json,String baseUrl,String moduleSecret,Duration connectTimeout,
        Duration requestTimeout,Duration tokenSkew,Clock clock) {
        this.json=json;this.baseUri=baseUri(baseUrl);this.moduleSecret=moduleSecret;
        this.requestTimeout=requestTimeout;this.tokenSkew=tokenSkew;this.clock=clock;
        if(moduleSecret==null||moduleSecret.isBlank()||connectTimeout.isZero()||connectTimeout.isNegative()
            ||requestTimeout.isZero()||requestTimeout.isNegative()||tokenSkew.isNegative())
            throw new IllegalStateException("CORE_HTTP_CONFIGURATION_REQUIRED");
        this.client=HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public OutboxStatus publish(String eventType,String targetModule,String payload) {
        if(!"CORE".equals(targetModule))throw permanent("CORE_HTTP_UNSUPPORTED_OUTBOX_TARGET");
        UUID eventId=validateEnvelope(eventType,payload);
        Token token=token();
        HttpResponse<String> response=sendEvent(eventId,payload,token);
        if(response.statusCode()==401){
            invalidate(token);
            token=token();
            response=sendEvent(eventId,payload,token);
            if(response.statusCode()==401)throw permanent("CORE_HTTP_EVENT_AUTHORIZATION_401");
        }
        return accepted(response,eventId)?OutboxStatus.CORE_ACCEPTED:throwUnexpected(response.statusCode());
    }

    private HttpResponse<String> sendEvent(UUID eventId,String payload,Token token) {
        HttpRequest request=HttpRequest.newBuilder(endpoint("/api/v1/events"))
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

    private synchronized Token token() {
        Instant now=clock.instant();
        if(cachedToken!=null&&now.isBefore(cachedToken.expiresAt()))return cachedToken;
        ObjectNode body=json.createObjectNode().put("module","rentas").put("secret",moduleSecret);
        HttpRequest request=HttpRequest.newBuilder(endpoint("/api/v1/auth/module-token"))
            .timeout(requestTimeout).header("Content-Type","application/json").header("Accept","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response=send(request,"CORE_HTTP_TOKEN_TRANSPORT_FAILED");
        if(response.statusCode()==401||response.statusCode()==403)
            throw permanent("CORE_HTTP_TOKEN_AUTHORIZATION_"+response.statusCode());
        if(response.statusCode()==422)throw permanent("CORE_HTTP_TOKEN_CONFIGURATION_REJECTED");
        if(response.statusCode()==408||response.statusCode()==429||response.statusCode()>=500)
            throw retryable("CORE_HTTP_TOKEN_RETRYABLE_"+response.statusCode());
        if(response.statusCode()!=200)throw permanent("CORE_HTTP_TOKEN_REJECTED_"+response.statusCode());
        try {
            JsonNode tokenResponse=json.readTree(response.body());
            String value=tokenResponse.path("accessToken").asText();
            long expiresIn=tokenResponse.path("expiresIn").asLong(0);
            String tokenType=tokenResponse.path("tokenType").asText("Bearer");
            String module=tokenResponse.path("module").asText();
            if(value.isBlank()||expiresIn<=0||!"Bearer".equalsIgnoreCase(tokenType)||!"rentas".equalsIgnoreCase(module))
                throw permanent("CORE_HTTP_TOKEN_RESPONSE_INVALID");
            cachedToken=new Token(value,now.plusSeconds(expiresIn).minus(tokenSkew));
            return cachedToken;
        } catch(CoreHttpPublishException ex){throw ex;}
        catch(Exception ex){throw retryable("CORE_HTTP_TOKEN_RESPONSE_INVALID");}
    }

    private synchronized void invalidate(Token used) {
        if(cachedToken==used)cachedToken=null;
    }

    private HttpResponse<String> send(HttpRequest request,String transportCode) {
        try{return client.send(request,HttpResponse.BodyHandlers.ofString());}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw retryable(transportCode);}
        catch(IOException|RuntimeException ex){throw retryable(transportCode);}
    }

    private URI endpoint(String path){return baseUri.resolve(path);}

    private static URI baseUri(String value) {
        try {
            URI uri=URI.create(value);
            String scheme=uri.getScheme(),host=uri.getHost();
            boolean local=host!=null&&(host.equalsIgnoreCase("localhost")||host.equals("127.0.0.1")||host.equals("::1"));
            if(!uri.isAbsolute()||host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                ||(uri.getPath()!=null&&!uri.getPath().isEmpty()&&!"/".equals(uri.getPath()))
                ||(!"https".equalsIgnoreCase(scheme)&&!("http".equalsIgnoreCase(scheme)&&local)))
                throw new IllegalArgumentException();
            return URI.create(scheme+"://"+uri.getRawAuthority()+"/");
        } catch(Exception ex){throw new IllegalStateException("CORE_HTTP_BASE_URL_INVALID");}
    }

    private static OutboxStatus throwUnexpected(int status){throw permanent("CORE_HTTP_UNEXPECTED_RESPONSE_"+status);}
    private static CoreHttpPublishException permanent(String code){return new CoreHttpPublishException(code,false);}
    private static CoreHttpPublishException retryable(String code){return new CoreHttpPublishException(code,true);}
    private record Token(String value,Instant expiresAt) {}
}

final class CoreHttpPublishException extends RuntimeException {
    private final boolean retryable;
    CoreHttpPublishException(String code,boolean retryable){super(code);this.retryable=retryable;}
    String code(){return getMessage();}
    boolean retryable(){return retryable;}
}
