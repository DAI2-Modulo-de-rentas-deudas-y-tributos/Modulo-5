package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
class CoreModuleTokenService {
    private final ObjectMapper json;
    private final String baseUrl,moduleSecret;
    private final Duration connectTimeout,requestTimeout,tokenSkew;
    private final Clock clock;
    private Configuration configuration;
    private CachedToken cachedToken;

    @Autowired
    CoreModuleTokenService(ObjectMapper json,
        @Value("${rentas.core.http.base-url:}") String baseUrl,
        @Value("${rentas.core.http.module-secret:}") String moduleSecret,
        @Value("${rentas.core.http.connect-timeout:3s}") Duration connectTimeout,
        @Value("${rentas.core.http.request-timeout:5s}") Duration requestTimeout,
        @Value("${rentas.core.http.token-expiry-skew:PT30S}") Duration tokenSkew) {
        this(json,baseUrl,moduleSecret,connectTimeout,requestTimeout,tokenSkew,Clock.systemUTC());
    }

    CoreModuleTokenService(ObjectMapper json,String baseUrl,String moduleSecret,Duration connectTimeout,
        Duration requestTimeout,Duration tokenSkew,Clock clock) {
        this.json=json;this.baseUrl=baseUrl;this.moduleSecret=moduleSecret;
        this.connectTimeout=connectTimeout;this.requestTimeout=requestTimeout;this.tokenSkew=tokenSkew;this.clock=clock;
    }

    synchronized void validateConfiguration(){configuration();}

    URI endpoint(String path){return configuration().baseUri().resolve(path);}

    HttpClient httpClient(){return configuration().client();}

    Duration requestTimeout(){return requestTimeout;}

    synchronized AccessToken accessToken() {
        Instant now=clock.instant();
        if(cachedToken!=null&&now.isBefore(cachedToken.expiresAt()))return cachedToken.token();
        Configuration config=configuration();
        ObjectNode body=json.createObjectNode().put("module","rentas").put("secret",moduleSecret);
        HttpRequest request=HttpRequest.newBuilder(config.baseUri().resolve("/api/v1/auth/module-token"))
            .timeout(requestTimeout).header("Content-Type","application/json").header("Accept","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response=send(config.client(),request,"CORE_HTTP_TOKEN_TRANSPORT_FAILED");
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
            cachedToken=new CachedToken(new AccessToken(value),now.plusSeconds(expiresIn).minus(tokenSkew));
            return cachedToken.token();
        } catch(CoreHttpPublishException ex){throw ex;}
        catch(Exception ex){throw retryable("CORE_HTTP_TOKEN_RESPONSE_INVALID");}
    }

    synchronized void invalidate(AccessToken used) {
        if(cachedToken!=null&&cachedToken.token()==used)cachedToken=null;
    }

    private synchronized Configuration configuration() {
        if(configuration!=null)return configuration;
        if(moduleSecret==null||moduleSecret.isBlank()||connectTimeout.isZero()||connectTimeout.isNegative()
            ||requestTimeout.isZero()||requestTimeout.isNegative()||tokenSkew.isNegative())
            throw new IllegalStateException("CORE_HTTP_CONFIGURATION_REQUIRED");
        URI baseUri=baseUri(baseUrl);
        configuration=new Configuration(baseUri,HttpClient.newBuilder().connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER).build());
        return configuration;
    }

    private HttpResponse<String> send(HttpClient client,HttpRequest request,String transportCode) {
        try{return client.send(request,HttpResponse.BodyHandlers.ofString());}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw retryable(transportCode);}
        catch(IOException|RuntimeException ex){throw retryable(transportCode);}
    }

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

    private static CoreHttpPublishException permanent(String code){return new CoreHttpPublishException(code,false);}
    private static CoreHttpPublishException retryable(String code){return new CoreHttpPublishException(code,true);}
    private record Configuration(URI baseUri,HttpClient client) {}
    record AccessToken(String value) {}
    private record CachedToken(AccessToken token,Instant expiresAt) {}
}
