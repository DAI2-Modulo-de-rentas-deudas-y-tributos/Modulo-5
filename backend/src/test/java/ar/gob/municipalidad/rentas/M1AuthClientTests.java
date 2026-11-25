package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class M1AuthClientTests {
    private static final String CITIZEN="b63f5a2e-4b71-46e7-8b01-381c85d88f62";
    private static final String OK_BODY="{\"user_id\":8452,\"uuid\":\""+CITIZEN+"\",\"citizenId\":\""+CITIZEN+"\",\"actorId\":\""+CITIZEN
        +"\",\"actorType\":\"CIUDADANO\",\"role\":\"PERSONA\",\"auth\":true,\"nombre\":\"Ana\",\"apellido\":\"Paz\"}";

    @Test void sendsCredentialsAndClientAddressAndReturnsCanonicalCitizen() throws Exception {
        AtomicReference<String> body=new AtomicReference<>();AtomicReference<String> forwarded=new AtomicReference<>();
        try(StubM1 m1=new StubM1(exchange->{
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/auth/usuario/login");
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            forwarded.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            respondLogin(exchange,200,OK_BODY);
        })) {
            assertThat(client(m1.url()).login("ana@example.invalid","clave-fixture","203.0.113.7")).isEqualTo(UUID.fromString(CITIZEN));
            assertThat(new ObjectMapper().readTree(body.get()).path("gmail").asText()).isEqualTo("ana@example.invalid");
            assertThat(forwarded.get()).isEqualTo("203.0.113.7");
        }
    }

    @Test void omitsForwardedHeaderWithoutClientAddress() throws Exception {
        AtomicReference<String> forwarded=new AtomicReference<>("unset");
        try(StubM1 m1=new StubM1(exchange->{forwarded.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));respondLogin(exchange,200,OK_BODY);})) {
            assertThat(client(m1.url()).login("ana@example.invalid","clave-fixture"," ")).isEqualTo(UUID.fromString(CITIZEN));
            assertThat(forwarded.get()).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter='|',value={
        "{\"auth\":false}|INVALID_CREDENTIALS",
        "{\"auth\":true,\"actorType\":\"EMPLEADO\",\"actorId\":\""+CITIZEN+"\"}|M1_ACTOR_NOT_ALLOWED",
        "{\"auth\":true,\"actorType\":\"CIUDADANO\"}|M1_INVALID_RESPONSE",
        "{\"auth\":true,\"actorType\":\"CIUDADANO\",\"citizenId\":\"no-uuid\"}|M1_INVALID_RESPONSE",
        "{\"auth\":true,\"actorType\":\"CIUDADANO\",\"citizenId\":\""+CITIZEN+"\",\"actorId\":\"8d7c3c7e-1f4d-4d7b-9a55-0a0b8a3f4c11\"}|M1_INVALID_RESPONSE",
        "{\"auth\":true,\"actorType\":1,\"citizenId\":\""+CITIZEN+"\"}|M1_INVALID_RESPONSE",
        "{\"auth\":\"true\"}|M1_INVALID_RESPONSE",
        "[]|M1_INVALID_RESPONSE",
        "no-json|M1_INVALID_RESPONSE"
    })
    void rejectsLoginBodiesThatDoNotAccreditACitizen(String body,String code) throws Exception {
        try(StubM1 m1=new StubM1(exchange->respondLogin(exchange,200,body))) {
            assertThatThrownBy(()->client(m1.url()).login("ana@example.invalid","clave-fixture",null))
                .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo(code));
        }
    }

    @Test void requiresJsonContentTypeAndBearerTransport() throws Exception {
        try(StubM1 m1=new StubM1(exchange->{exchange.getResponseHeaders().add("Content-Type","text/html");respond(exchange,200,OK_BODY);})) {
            assertThatThrownBy(()->client(m1.url()).login("a@example.invalid","x",null))
                .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("M1_INVALID_RESPONSE"));
        }
        try(StubM1 m1=new StubM1(exchange->{exchange.getResponseHeaders().add("Content-Type","application/json");respond(exchange,200,OK_BODY);})) {
            assertThatThrownBy(()->client(m1.url()).login("a@example.invalid","x",null))
                .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("M1_INVALID_RESPONSE"));
        }
        try(StubM1 m1=new StubM1(exchange->respondLogin(exchange,200,"{\"pad\":\""+"x".repeat(70_000)+"\"}"))) {
            assertThatThrownBy(()->client(m1.url()).login("a@example.invalid","x",null))
                .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("M1_INVALID_RESPONSE"));
        }
    }

    @ParameterizedTest
    @CsvSource({"401,INVALID_CREDENTIALS,401","403,M1_ACCOUNT_FORBIDDEN,403","429,M1_RATE_LIMITED,429",
        "502,M1_UNAVAILABLE,503","503,M1_UNAVAILABLE,503","504,M1_UNAVAILABLE,503","500,M1_UPSTREAM_ERROR,502","400,M1_UPSTREAM_ERROR,502"})
    void mapsM1StatusWithoutExposingItsBody(int m1Status,String code,int status) throws Exception {
        try(StubM1 m1=new StubM1(exchange->{exchange.getResponseHeaders().add("Retry-After","42");respond(exchange,m1Status,"{\"message\":\"detalle interno\"}");})) {
            assertThatThrownBy(()->client(m1.url()).login("a@example.invalid","x",null))
                .isInstanceOfSatisfying(BusinessException.class,ex->{
                    assertThat(ex.code).isEqualTo(code);
                    assertThat(ex.status).isEqualTo(status);
                    assertThat(ex.getMessage()).doesNotContain("detalle interno");
                    if(m1Status==429)assertThat(ex.retryAfterSeconds).isEqualTo(42);
                });
        }
    }

    @Test void rateLimitWithoutNumericRetryAfterUsesDefault() throws Exception {
        try(StubM1 m1=new StubM1(exchange->respond(exchange,429,"{}"))) {
            assertThatThrownBy(()->client(m1.url()).login("a@example.invalid","x",null))
                .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.retryAfterSeconds).isEqualTo(60));
        }
    }

    @Test void slowM1IsReportedAsTimeoutAndClosedPortAsUnavailable() throws Exception {
        try(StubM1 m1=new StubM1(exchange->{
            try { Thread.sleep(1_000); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
            respondLogin(exchange,200,OK_BODY);
        })) {
            M1AuthClient slow=new M1AuthClient(new ObjectMapper(),m1.url(),Duration.ofSeconds(2),Duration.ofMillis(100));
            assertThatThrownBy(()->slow.login("a@example.invalid","x",null))
                .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("M1_TIMEOUT"));
        }
        String closed;
        try(StubM1 m1=new StubM1(exchange->respond(exchange,200,"{}"))) { closed=m1.url(); }
        assertThatThrownBy(()->client(closed).login("a@example.invalid","x",null))
            .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isIn("M1_UNAVAILABLE","M1_TIMEOUT"));
    }

    @ParameterizedTest
    @ValueSource(strings={"","http://ciudadanos.example.invalid","https://ciudadanos.example.invalid/api","https://user@ciudadanos.example.invalid",
        "https://ciudadanos.example.invalid?x=1","https://ciudadanos.example.invalid#f","ftp://ciudadanos.example.invalid","https:// bad"})
    void rejectsUnsafeBaseUrls(String baseUrl) {
        assertThatThrownBy(()->M1AuthClient.origin(baseUrl)).isInstanceOf(IllegalStateException.class);
    }

    @Test void acceptsHttpsOriginsAndLoopbackHttp() {
        assertThat(M1AuthClient.origin(" https://Ciudadanos.example.invalid/ ")).isEqualTo("https://Ciudadanos.example.invalid");
        assertThat(M1AuthClient.origin("http://127.0.0.1:8090")).isEqualTo("http://127.0.0.1:8090");
        assertThat(M1AuthClient.origin("HTTP://localhost:8090")).isEqualTo("http://localhost:8090");
    }

    @Test void rejectsInvalidTimeouts() {
        ObjectMapper json=new ObjectMapper();
        assertThatThrownBy(()->new M1AuthClient(json,"https://ciudadanos.example.invalid",Duration.ZERO,Duration.ofSeconds(1)))
            .hasMessage("M1_AUTH_TIMEOUTS_INVALID");
        assertThatThrownBy(()->new M1AuthClient(json,"https://ciudadanos.example.invalid",Duration.ofSeconds(1),Duration.ofSeconds(-1)))
            .hasMessage("M1_AUTH_TIMEOUTS_INVALID");
    }

    private static M1AuthClient client(String url) {
        return new M1AuthClient(new ObjectMapper(),url,Duration.ofSeconds(2),Duration.ofSeconds(2));
    }

    static void respondLogin(HttpExchange exchange,int status,String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type","application/json");
        exchange.getResponseHeaders().add("Authorization","Bearer token-de-m1");
        respond(exchange,status,body);
    }

    static void respond(HttpExchange exchange,int status,String body) throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status,bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    interface Handler { void handle(HttpExchange exchange) throws IOException; }

    /** M1 simulado en loopback; nunca contacta el servicio real. */
    static final class StubM1 implements AutoCloseable {
        private final HttpServer server;
        StubM1(Handler handler) throws IOException {
            server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
            server.createContext("/",handler::handle);
            server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        @Override public void close(){server.stop(0);}
    }
}
