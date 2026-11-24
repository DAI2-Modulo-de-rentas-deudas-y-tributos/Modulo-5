package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

@Component
class CoreReadOnlyClient {
    private final CoreModuleTokenService tokens;
    private final ObjectMapper json;

    CoreReadOnlyClient(CoreModuleTokenService tokens,ObjectMapper json) {
        this.tokens=tokens;this.json=json;
    }

    JsonNode listDeadLetters(Map<String,String> filters) {
        return get("/api/v1/dlq",filters);
    }

    JsonNode getDeadLetter(String id) {
        return get("/api/v1/dlq/"+pathSegment(id),Map.of());
    }

    JsonNode getDeadLetterAudit(String id) {
        return get("/api/v1/dlq/"+pathSegment(id)+"/audit",Map.of());
    }

    JsonNode listDeliveries(Map<String,String> filters) {
        return get("/api/v1/deliveries",filters);
    }

    private JsonNode get(String path,Map<String,String> filters) {
        URI endpoint=tokens.endpoint(path);
        String query=filters.entrySet().stream()
            .filter(entry->entry.getKey()!=null&&!entry.getKey().isBlank()&&entry.getValue()!=null)
            .map(entry->encode(entry.getKey())+"="+encode(entry.getValue()))
            .collect(Collectors.joining("&"));
        URI uri=query.isEmpty()?endpoint:URI.create(endpoint+"?"+query);
        CoreModuleTokenService.AccessToken token=tokens.accessToken();
        HttpResponse<String> response=send(uri,token);
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

    private HttpResponse<String> send(URI uri,CoreModuleTokenService.AccessToken token) {
        HttpRequest request=HttpRequest.newBuilder(uri).timeout(tokens.requestTimeout())
            .header("Authorization","Bearer "+token.value()).header("Accept","application/json").GET().build();
        try{return tokens.httpClient().send(request,HttpResponse.BodyHandlers.ofString());}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw retryable("CORE_READ_TRANSPORT_FAILED");}
        catch(IOException|RuntimeException ex){throw retryable("CORE_READ_TRANSPORT_FAILED");}
    }

    private static String pathSegment(String id) {
        if(id==null||id.isBlank()||id.contains("/")||id.contains("\\"))
            throw permanent("CORE_READ_INVALID_DEAD_LETTER_ID");
        return encode(id);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");
    }

    private static CoreHttpPublishException permanent(String code){return new CoreHttpPublishException(code,false);}
    private static CoreHttpPublishException retryable(String code){return new CoreHttpPublishException(code,true);}
}
