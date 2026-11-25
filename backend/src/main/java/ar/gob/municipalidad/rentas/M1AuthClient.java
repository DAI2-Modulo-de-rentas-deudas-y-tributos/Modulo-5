package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Login de usuarios de M1 ({@code POST /auth/usuario/login}), igual que M8: M5 reenvía las
 * credenciales, acepta sólo ciudadanos y descarta el JWT de M1. No necesita el secreto HS256 de M1.
 */
@Component
@ConditionalOnProperty(name="rentas.m1.auth.enabled",havingValue="true")
class M1AuthClient {
    private static final int MAX_RESPONSE_BYTES=65_536;
    private static final Pattern BEARER=Pattern.compile("Bearer +\\S+",Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID_TEXT=Pattern.compile("\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}");
    private static final Set<String> LOOPBACK=Set.of("localhost","127.0.0.1","[::1]");
    private static final int DEFAULT_RETRY_AFTER_SECONDS=60;

    private final ObjectMapper json;
    private final URI loginUri;
    private final Duration readTimeout;
    private final HttpClient client;

    M1AuthClient(ObjectMapper json,
        @Value("${rentas.m1.auth.base-url:}") String baseUrl,
        @Value("${rentas.m1.auth.connect-timeout:5s}") Duration connectTimeout,
        @Value("${rentas.m1.auth.read-timeout:25s}") Duration readTimeout) {
        if(!positive(connectTimeout)||!positive(readTimeout))throw new IllegalStateException("M1_AUTH_TIMEOUTS_INVALID");
        this.json=json;
        this.loginUri=URI.create(origin(baseUrl)+"/auth/usuario/login");
        this.readTimeout=readTimeout;
        this.client=HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * Identidad que devuelve M1. {@code dni}, {@code cuit} y {@code displayName} quedan en null si M1
     * no los manda con el formato esperado: sin ellos no se puede crear la referencia local.
     */
    record M1Citizen(UUID citizenId,String dni,String cuit,String displayName) {
        boolean registrable(){return dni!=null&&cuit!=null&&displayName!=null;}
        @Override public String toString(){return "M1Citizen["+citizenId+"]";}
    }

    /** Devuelve la identidad del ciudadano. Los mensajes de M1 nunca llegan al llamador. */
    M1Citizen login(String email,String password,String clientAddress) {
        String body=json.createObjectNode().put("gmail",email).put("password",password).toString();
        HttpRequest.Builder request=HttpRequest.newBuilder(loginUri).timeout(readTimeout)
            .header("Content-Type","application/json").header("Accept","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        // M1 limita intentos por la primera IP de X-Forwarded-For: sin esto, todos los vecinos
        // compartirían la IP de salida de M5 y unos pocos errores bloquearían el ingreso de todos.
        if(clientAddress!=null&&!clientAddress.isBlank())request.header("X-Forwarded-For",clientAddress);
        try {
            HttpResponse<InputStream> response=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream stream=response.body()) {
                if(response.statusCode()!=200)throw failure(response);
                String contentType=response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                if(!contentType.startsWith("application/json"))throw invalidResponse();
                // El acuerdo de M1 incluye el Bearer en la respuesta: se verifica su transporte, no su firma.
                if(!BEARER.matcher(response.headers().firstValue("Authorization").orElse("")).matches())throw invalidResponse();
                byte[] bytes=stream.readNBytes(MAX_RESPONSE_BYTES+1);
                if(bytes.length==0||bytes.length>MAX_RESPONSE_BYTES)throw invalidResponse();
                return citizen(readTree(bytes));
            }
        } catch(HttpTimeoutException ex) {
            throw new BusinessException("M1_TIMEOUT","Ciudadanos no respondió a tiempo. Reintentá en unos segundos",504);
        } catch(IOException ex) {
            throw unavailable();
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private JsonNode readTree(byte[] bytes) {
        try { return json.readTree(bytes); }
        catch(IOException ex) { throw invalidResponse(); }
    }

    private M1Citizen citizen(JsonNode root) {
        if(!root.isObject()||!root.path("auth").isBoolean())throw invalidResponse();
        if(!root.get("auth").asBoolean())throw invalidCredentials();
        if(!"CIUDADANO".equals(text(root,"actorType")))
            throw new BusinessException("M1_ACTOR_NOT_ALLOWED","El ingreso con Ciudadanos es sólo para vecinos. El personal ingresa con su cuenta de Rentas",403);
        UUID citizenId=uuid(text(root,"citizenId"));
        for(String alias:new String[]{"actorId","uuid"}) {
            String value=text(root,alias);
            if(value!=null&&!uuid(value).equals(citizenId))throw invalidResponse();
        }
        // DNI y CUIL llegan como números: M1 manda dni=0 cuando su documento no es numérico.
        return new M1Citizen(citizenId,digits(root.get("dni"),"[1-9][0-9]{5,8}"),digits(root.get("cuit"),"[0-9]{11}"),fullName(root));
    }

    private static String digits(JsonNode value,String pattern) {
        if(value==null||!(value.isIntegralNumber()||value.isTextual()))return null;
        String text=value.asText().trim();
        return text.matches(pattern)?text:null;
    }

    private static String fullName(JsonNode root) {
        JsonNode first=root.get("nombre"),last=root.get("apellido");
        if(first==null||last==null||!first.isTextual()||!last.isTextual()||first.asText().isBlank()||last.asText().isBlank())return null;
        String name=first.asText().trim()+" "+last.asText().trim();
        return name.length()<=200?name:null;
    }

    private static String text(JsonNode root,String field) {
        JsonNode value=root.get(field);
        if(value==null||value.isNull())return null;
        if(!value.isTextual())throw invalidResponse();
        return value.asText();
    }

    private static UUID uuid(String value) {
        if(value==null||!UUID_TEXT.matcher(value).matches())throw invalidResponse();
        return UUID.fromString(value);
    }

    private static BusinessException failure(HttpResponse<?> response) {
        return switch(response.statusCode()) {
            case 401 -> invalidCredentials();
            case 403 -> new BusinessException("M1_ACCOUNT_FORBIDDEN","Tu cuenta de Ciudadanos está inactiva o bloqueada",403);
            case 429 -> new BusinessException("M1_RATE_LIMITED","Demasiados intentos en Ciudadanos. Reintentá cuando venza el bloqueo",429,
                retryAfter(response.headers().firstValue("Retry-After").orElse("")));
            case 502, 503, 504 -> unavailable();
            default -> new BusinessException("M1_UPSTREAM_ERROR","Ciudadanos no pudo validar el ingreso",502);
        };
    }

    private static int retryAfter(String header) {
        try { return Math.max(1,Integer.parseInt(header.trim())); }
        catch(NumberFormatException ex) { return DEFAULT_RETRY_AFTER_SECONDS; }
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException("INVALID_CREDENTIALS","Correo o contraseña incorrectos",401);
    }

    private static BusinessException unavailable() {
        return new BusinessException("M1_UNAVAILABLE","Ciudadanos no está disponible en este momento. Reintentá en unos segundos",503);
    }

    private static BusinessException invalidResponse() {
        return new BusinessException("M1_INVALID_RESPONSE","Ciudadanos devolvió una respuesta inesperada",502);
    }

    private static boolean positive(Duration value) {
        return value!=null&&!value.isZero()&&!value.isNegative();
    }

    /** Sólo un origen HTTPS sin credenciales, ruta, query ni fragmento; HTTP únicamente en loopback para pruebas. */
    static String origin(String baseUrl) {
        URI uri;
        try { uri=URI.create(baseUrl==null?"":baseUrl.trim()); }
        catch(IllegalArgumentException ex) { throw new IllegalStateException("M1_AUTH_BASE_URL_INVALID",ex); }
        String path=uri.getRawPath()==null?"":uri.getRawPath();
        boolean secure="https".equalsIgnoreCase(uri.getScheme());
        boolean loopback="http".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null&&LOOPBACK.contains(uri.getHost().toLowerCase(Locale.ROOT));
        if(uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
            ||!path.chars().allMatch(c->c=='/')||!(secure||loopback))
            throw new IllegalStateException("M1_AUTH_BASE_URL_INVALID");
        return uri.getScheme().toLowerCase(Locale.ROOT)+"://"+uri.getRawAuthority();
    }
}
