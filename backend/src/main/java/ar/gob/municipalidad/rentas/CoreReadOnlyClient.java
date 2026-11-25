package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

@Component
class CoreReadOnlyClient {
    private static final int MAX_RESPONSE_BYTES=1_048_576;
    private static final Set<String> DEAD_LETTER_FILTERS=Set.of("status","reasonCode","page");
    private static final Set<String> DELIVERY_FILTERS=Set.of("eventId","targetModule","status","page");
    private static final Set<String> DEAD_LETTER_REASONS=Set.of(
        "SCHEMA_VIOLATION","DELIVERY_FAILED","MALFORMED_MESSAGE","SOURCE_UNVERIFIED");
    private final CoreModuleTokenService tokens;
    private final ObjectMapper json;

    CoreReadOnlyClient(CoreModuleTokenService tokens,ObjectMapper json) {
        this.tokens=tokens;this.json=json;
    }

    JsonNode listDeadLetters(Map<String,String> filters) {
        return get("/api/v1/dlq",filters,DEAD_LETTER_FILTERS);
    }

    JsonNode getDeadLetter(String id) {
        return get("/api/v1/dlq/"+pathSegment(id),Map.of(),Set.of());
    }

    JsonNode getDeadLetterAudit(String id) {
        return get("/api/v1/dlq/"+pathSegment(id)+"/audit",Map.of(),Set.of());
    }

    JsonNode listDeliveries(Map<String,String> filters) {
        return get("/api/v1/deliveries",filters,DELIVERY_FILTERS);
    }

    private JsonNode get(String path,Map<String,String> filters,Set<String> allowedFilters) {
        String query=query(filters,allowedFilters);
        URI endpoint=tokens.endpoint(path);
        URI uri=query.isEmpty()?endpoint:URI.create(endpoint+"?"+query);
        CoreModuleTokenService.AccessToken token=tokens.accessToken();
        CoreReadResponse response=send(uri,token);
        if(response.statusCode()==401) {
            tokens.invalidate(token);
            response=send(uri,tokens.accessToken());
            if(response.statusCode()==401)throw permanent("CORE_READ_AUTHORIZATION_401");
        }
        if(response.statusCode()==403)throw permanent("CORE_READ_AUTHORIZATION_403");
        if(response.statusCode()==404)throw permanent("CORE_READ_NOT_FOUND");
        if(response.statusCode()==408||response.statusCode()==429||response.statusCode()>=500)
            throw retryable("CORE_READ_RETRYABLE_"+response.statusCode());
        if(response.statusCode()!=200)throw permanent("CORE_READ_REJECTED_"+response.statusCode());
        try{return json.readTree(response.body());}
        catch(Exception ex){throw permanent("CORE_READ_RESPONSE_INVALID");}
    }

    private CoreReadResponse send(URI uri,CoreModuleTokenService.AccessToken token) {
        java.time.Duration timeout=tokens.requestTimeout();
        long started=System.nanoTime();
        HttpRequest request=HttpRequest.newBuilder(uri).timeout(timeout)
            .header("Authorization","Bearer "+token.value()).header("Accept","application/json").GET().build();
        try {
            HttpResponse<byte[]> response=tokens.httpClient().send(request,
                info->new BoundedBodySubscriber(MAX_RESPONSE_BYTES,
                    timeout.minusNanos(Math.max(0,System.nanoTime()-started))));
            byte[] bytes=response.body();
            if(bytes.length>MAX_RESPONSE_BYTES)throw permanent("CORE_READ_RESPONSE_TOO_LARGE");
            return new CoreReadResponse(response.statusCode(),new String(bytes,StandardCharsets.UTF_8));
        }
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw retryable("CORE_READ_TRANSPORT_FAILED");}
        catch(CoreHttpPublishException ex){throw ex;}
        catch(IOException|RuntimeException ex){throw retryable("CORE_READ_TRANSPORT_FAILED");}
    }

    private static String pathSegment(String id) {
        if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")||id.equals(".")||id.equals(".."))
            throw permanent("CORE_READ_INVALID_DEAD_LETTER_ID");
        return encode(id);
    }

    private static String query(Map<String,String> filters,Set<String> allowedFilters) {
        if(filters==null)throw permanent("CORE_READ_INVALID_QUERY_PARAMETER");
        return filters.entrySet().stream().map(entry->{
            String key=entry.getKey(),value=entry.getValue();
            if(key==null||!allowedFilters.contains(key)||value==null||value.isBlank())
                throw permanent("CORE_READ_INVALID_QUERY_PARAMETER");
            if("page".equals(key))boundedInteger(value,0,1_000_000);
            else if("status".equals(key)&&!validStatus(value,allowedFilters))
                throw permanent("CORE_READ_INVALID_QUERY_PARAMETER");
            else if("reasonCode".equals(key)&&!DEAD_LETTER_REASONS.contains(value))
                throw permanent("CORE_READ_INVALID_QUERY_PARAMETER");
            else if("targetModule".equals(key)&&!"rentas".equals(value))
                throw permanent("CORE_READ_INVALID_QUERY_PARAMETER");
            else if("eventId".equals(key))try{java.util.UUID.fromString(value);}
            catch(IllegalArgumentException ex){throw permanent("CORE_READ_INVALID_QUERY_PARAMETER");}
            return encode(key)+"="+encode(value);
        }).sorted().collect(java.util.stream.Collectors.joining("&"));
    }

    private static boolean validStatus(String value,Set<String> filters) {
        if(filters.contains("reasonCode"))return "OPEN".equals(value);
        return Set.of("DEAD","DELIVERED").contains(value);
    }

    private static void boundedInteger(String value,int minimum,int maximum) {
        try {int parsed=Integer.parseInt(value);if(parsed<minimum||parsed>maximum)throw permanent("CORE_READ_INVALID_QUERY_PARAMETER");}
        catch(NumberFormatException ex){throw permanent("CORE_READ_INVALID_QUERY_PARAMETER");}
    }

    private record CoreReadResponse(int statusCode,String body) {}

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private static final byte[] TOO_LARGE=new byte[MAX_RESPONSE_BYTES+1];
        private final int maximumBytes;
        private final ByteArrayOutputStream body=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private volatile Flow.Subscription subscription;

        private BoundedBodySubscriber(int maximumBytes,java.time.Duration timeout){
            this.maximumBytes=maximumBytes;
            long timeoutMillis=Math.max(1,timeout.toMillis());
            CompletableFuture.delayedExecutor(timeoutMillis,java.util.concurrent.TimeUnit.MILLISECONDS).execute(()->{
                if(result.completeExceptionally(new HttpTimeoutException("Core response body timed out"))
                    &&subscription!=null)subscription.cancel();
            });
        }
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription subscription){
            this.subscription=subscription;
            if(result.isDone())subscription.cancel();else subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers){
            if(result.isDone())return;
            long incoming=buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if(incoming>maximumBytes-body.size()){
                subscription.cancel();result.complete(TOO_LARGE);return;
            }
            for(ByteBuffer buffer:buffers){
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);body.writeBytes(chunk);
            }
            if(!result.isDone())subscription.request(1);
        }
        @Override public void onError(Throwable error){result.completeExceptionally(error);}
        @Override public void onComplete(){result.complete(body.toByteArray());}
    }

    private static String encode(String value) {
        return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");
    }

    private static CoreHttpPublishException permanent(String code){return new CoreHttpPublishException(code,false);}
    private static CoreHttpPublishException retryable(String code){return new CoreHttpPublishException(code,true);}
}
