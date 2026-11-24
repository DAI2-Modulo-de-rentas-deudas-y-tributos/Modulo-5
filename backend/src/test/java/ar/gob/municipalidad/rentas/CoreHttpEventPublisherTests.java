package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreHttpEventPublisherTests {
    private static final String SECRET="local-test-secret";
    private static final String EVENT_ID="fc444493-302a-4f30-a2a1-9360eaa26ed7";
    private static final String PAYLOAD="{\"eventId\":\""+EVENT_ID+"\",\"eventType\":\"exemptionRequested\",\"eventVersion\":\"1.0\",\"occurredAt\":\"2026-10-31T12:30:00-03:00\",\"sourceModule\":\"rentas\",\"correlationId\":\"ec94049c-0fc7-4d80-b8cf-0b8f6a69dc4a\",\"causationId\":null,\"data\":{\"requestId\":\"synthetic\"}}";
    private final ObjectMapper json=new ObjectMapper();

    @Test void tokenServiceCanAuthenticateWithoutAnEventPublisher() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        try(TestServer server=server(exchange->{
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/v1/auth/module-token");
            assertThat(new String(exchange.getRequestBody().readAllBytes())).isEqualTo("{\"module\":\"rentas\",\"secret\":\""+SECRET+"\"}");
            requests.incrementAndGet();respond(exchange,200,token("read-only-client",3600));
        })){
            CoreModuleTokenService tokens=new CoreModuleTokenService(json,server.uri(),SECRET,Duration.ofSeconds(1),
                Duration.ofSeconds(2),Duration.ofSeconds(1),Clock.systemUTC());
            assertThat(tokens.accessToken().value()).isEqualTo("read-only-client");
            assertThat(tokens.accessToken().value()).isEqualTo("read-only-client");
            assertThat(tokens.accessToken().toString()).doesNotContain("read-only-client").isEqualTo("AccessToken[REDACTED]");
            assertThat(requests.get()).isEqualTo(1);
        }
    }

    @Test void inactiveTokenClientDoesNotRequireHttpCredentials() {
        CoreModuleTokenService tokens=new CoreModuleTokenService(json,"","",Duration.ofSeconds(1),
            Duration.ofSeconds(2),Duration.ofSeconds(1),Clock.systemUTC());
        assertThatThrownBy(tokens::accessToken).isInstanceOf(IllegalStateException.class)
            .hasMessage("CORE_HTTP_CONFIGURATION_REQUIRED");
    }

    @Test void readOnlyClientUsesConfirmedRoutesAndForwardsFiltersWithSharedToken() throws Exception {
        AtomicInteger tokens=new AtomicInteger();List<String> paths=new ArrayList<>();
        try(TestServer server=server(exchange->{
            if(exchange.getRequestURI().getPath().equals("/api/v1/auth/module-token")){
                tokens.incrementAndGet();respond(exchange,200,token("read",3600));return;
            }
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer read");
            paths.add(exchange.getRequestURI().toString());respond(exchange,200,"{\"items\":[]}");
        })){
            CoreModuleTokenService moduleTokens=new CoreModuleTokenService(json,server.uri(),SECRET,
                Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(1),Clock.systemUTC());
            CoreReadOnlyClient client=new CoreReadOnlyClient(moduleTokens,json);
            assertThat(client.listDeadLetters(Map.of("status","DELIVERED","page","2")).path("items")).isEmpty();
            assertThat(client.getDeadLetter("dl-17").path("items")).isEmpty();
            assertThat(client.getDeadLetterAudit("dl-17").path("items")).isEmpty();
            assertThat(client.listDeliveries(Map.of("eventId",EVENT_ID)).path("items")).isEmpty();
            assertThat(paths).hasSize(4);
            assertThat(paths.get(0)).startsWith("/api/v1/dlq?").contains("status=DELIVERED","page=2");
            assertThat(paths).contains("/api/v1/dlq/dl-17","/api/v1/dlq/dl-17/audit",
                "/api/v1/deliveries?eventId="+EVENT_ID);
            assertThat(tokens.get()).isEqualTo(1);
        }
    }

    @Test void readOnlyClientRenewsTokenOnceAfterUnauthorizedResponse() throws Exception {
        AtomicInteger tokens=new AtomicInteger(),reads=new AtomicInteger();
        try(TestServer server=server(exchange->{
            if(exchange.getRequestURI().getPath().equals("/api/v1/auth/module-token")){
                respond(exchange,200,token("t"+tokens.incrementAndGet(),3600));return;
            }
            if(reads.incrementAndGet()==1)respond(exchange,401,"{}");else respond(exchange,200,"{}");
        })){
            var moduleTokens=new CoreModuleTokenService(json,server.uri(),SECRET,Duration.ofSeconds(1),
                Duration.ofSeconds(2),Duration.ofSeconds(1),Clock.systemUTC());
            assertThat(new CoreReadOnlyClient(moduleTokens,json).listDeliveries(Map.of()).isObject()).isTrue();
            assertThat(tokens.get()).isEqualTo(2);
            assertThat(reads.get()).isEqualTo(2);
        }
    }

    @Test void readOnlyClientDistinguishesNotFoundFromRetryableServiceLimits() throws Exception {
        for(int status:List.of(404,429))try(TestServer server=server(exchange->{
            if(exchange.getRequestURI().getPath().equals("/api/v1/auth/module-token"))respond(exchange,200,token("t",3600));
            else respond(exchange,status,"sensitive body");
        })){
            var moduleTokens=new CoreModuleTokenService(json,server.uri(),SECRET,Duration.ofSeconds(1),
                Duration.ofSeconds(2),Duration.ofSeconds(1),Clock.systemUTC());
            assertThatThrownBy(()->new CoreReadOnlyClient(moduleTokens,json).listDeliveries(Map.of()))
                .isInstanceOf(CoreHttpPublishException.class).satisfies(error->{
                    var failure=(CoreHttpPublishException)error;
                    assertThat(failure.retryable()).isEqualTo(status==429);
                    assertThat(failure.getMessage()).doesNotContain("sensitive");
                });
        }
    }

    @Test void readOnlyClientRejectsPathTraversalInDeadLetterIdBeforeNetworkCall() {
        var moduleTokens=new CoreModuleTokenService(json,"","",Duration.ofSeconds(1),Duration.ofSeconds(2),
            Duration.ofSeconds(1),Clock.systemUTC());
        for(String id:List.of("../events",".","..","a/b","a%2fb","a?query","a#fragment"))
            assertThatThrownBy(()->new CoreReadOnlyClient(moduleTokens,json).getDeadLetter(id))
                .isInstanceOf(CoreHttpPublishException.class).hasMessage("CORE_READ_INVALID_DEAD_LETTER_ID");
    }

    @Test void readOnlyClientAllowsOnlyBoundedKnownFiltersAndPagination() throws Exception {
        List<String> paths=new ArrayList<>();
        try(TestServer server=server(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("module-token")){respond(exchange,200,token("read",3600));return;}
            paths.add(exchange.getRequestURI().toString());respond(exchange,200,"{}");
        })){
            var client=new CoreReadOnlyClient(new CoreModuleTokenService(json,server.uri(),SECRET,Duration.ofSeconds(1),
                Duration.ofSeconds(2),Duration.ofSeconds(1),Clock.systemUTC()),json);
            client.listDeadLetters(Map.of("status","DELIVERED","page","0"));
            client.listDeliveries(Map.of("eventId",EVENT_ID,"page","1"));
            assertThat(paths).hasSize(2);
            assertThat(paths.get(0)).contains("page=0","status=DELIVERED");
            assertThat(paths.get(1)).contains("eventId="+EVENT_ID,"page=1");
        }
        var client=new CoreReadOnlyClient(new CoreModuleTokenService(json,"","",Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(1),Clock.systemUTC()),json);
        for(Map<String,String> invalid:List.of(Map.of("payload","private"),Map.of("page","-1"),Map.of("page","1000001"),Map.of("status","private status"),Map.of("eventId","not-a-uuid")))
            assertThatThrownBy(()->client.listDeliveries(invalid)).isInstanceOf(CoreHttpPublishException.class)
                .hasMessage("CORE_READ_INVALID_QUERY_PARAMETER");
    }

    @Test void readOnlyClientRejectsOversizedResponsesWithoutParsingTheBody() throws Exception {
        try(TestServer server=server(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("module-token")){respond(exchange,200,token("read",3600));return;}
            respond(exchange,200,"x".repeat(1_048_577));
        })){
            var client=new CoreReadOnlyClient(new CoreModuleTokenService(json,server.uri(),SECRET,Duration.ofSeconds(1),
                Duration.ofSeconds(2),Duration.ofSeconds(1),Clock.systemUTC()),json);
            assertThatThrownBy(()->client.listDeliveries(Map.of())).isInstanceOf(CoreHttpPublishException.class)
                .hasMessage("CORE_READ_RESPONSE_TOO_LARGE");
        }
    }

    @Test void postsSameEnvelopeWithBearerAndReturnsCoreAccepted() throws Exception {
        AtomicReference<String> sent=new AtomicReference<>();AtomicReference<String> authBody=new AtomicReference<>();
        try(TestServer server=server(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("module-token")){authBody.set(new String(exchange.getRequestBody().readAllBytes()));respond(exchange,200,token("first",3600));return;}
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer first");
            sent.set(new String(exchange.getRequestBody().readAllBytes()));respond(exchange,202,"{\"eventId\":\""+EVENT_ID+"\",\"accepted\":true,\"duplicate\":false}");
        })){
            assertThat(publisher(server.uri(),Clock.systemUTC()).publish("exemptionRequested","CORE",PAYLOAD)).isEqualTo(OutboxStatus.CORE_ACCEPTED);
            assertThat(sent.get()).isEqualTo(PAYLOAD);
            assertThat(authBody.get()).isEqualTo("{\"module\":\"rentas\",\"secret\":\""+SECRET+"\"}");
        }
    }

    @Test void duplicateResponseIsAcceptedWithoutChangingEventId() throws Exception {
        try(TestServer server=server(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("module-token"))respond(exchange,200,token("t",3600));
            else {assertThat(new String(exchange.getRequestBody().readAllBytes())).isEqualTo(PAYLOAD);respond(exchange,200,"{\"duplicate\":true,\"eventId\":\""+EVENT_ID+"\"}");}
        })){
            assertThat(publisher(server.uri(),Clock.systemUTC()).publish("exemptionRequested","CORE",PAYLOAD)).isEqualTo(OutboxStatus.CORE_ACCEPTED);
        }
    }

    @Test void emptyDuplicateResponseIsAcceptedAsDocumentedByCoreOpenApi() throws Exception {
        try(TestServer server=server(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("module-token"))respond(exchange,200,token("t",3600));
            else {assertThat(new String(exchange.getRequestBody().readAllBytes())).isEqualTo(PAYLOAD);respond(exchange,200,"");}
        })){
            assertThat(publisher(server.uri(),Clock.systemUTC()).publish("exemptionRequested","CORE",PAYLOAD)).isEqualTo(OutboxStatus.CORE_ACCEPTED);
        }
    }

    @Test void renewsExpiredTokenAndRetriesUnauthorizedWithOriginalEvent() throws Exception {
        AtomicInteger tokens=new AtomicInteger(),events=new AtomicInteger();List<String> bodies=new ArrayList<>();List<String> auth=new ArrayList<>();
        MutableClock clock=new MutableClock(Instant.parse("2026-10-31T15:00:00Z"));
        try(TestServer server=server(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("module-token")){int n=tokens.incrementAndGet();respond(exchange,200,token("t"+n,10));return;}
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));bodies.add(new String(exchange.getRequestBody().readAllBytes()));
            if(events.incrementAndGet()==1)respond(exchange,401,"{}");
            else respond(exchange,202,"{\"eventId\":\""+EVENT_ID+"\",\"accepted\":true}");
        })){
            CoreHttpEventPublisher publisher=publisher(server.uri(),clock);
            assertThat(publisher.publish("exemptionRequested","CORE",PAYLOAD)).isEqualTo(OutboxStatus.CORE_ACCEPTED);
            assertThat(auth).containsExactly("Bearer t1","Bearer t2");
            assertThat(bodies).containsExactly(PAYLOAD,PAYLOAD);
            clock.advance(Duration.ofSeconds(10));
            assertThat(publisher.publish("exemptionRequested","CORE",PAYLOAD)).isEqualTo(OutboxStatus.CORE_ACCEPTED);
            assertThat(tokens.get()).isEqualTo(3);
        }
    }

    @Test void validationAndAuthorizationFailuresAreTerminalAndDoNotExposeResponseBody() throws Exception {
        for(int status:List.of(403,422))try(TestServer server=server(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("module-token"))respond(exchange,200,token("t",3600));
            else respond(exchange,status,"private server detail");
        })){
            assertThatThrownBy(()->publisher(server.uri(),Clock.systemUTC()).publish("exemptionRequested","CORE",PAYLOAD))
                .isInstanceOf(CoreHttpPublishException.class).satisfies(error->{
                    CoreHttpPublishException failure=(CoreHttpPublishException)error;
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getMessage()).contains(String.valueOf(status)).doesNotContain("private");
                });
        }
    }

    @Test void repeatedUnauthorizedResponseStopsAfterOneTokenRenewal() throws Exception {
        AtomicInteger tokens=new AtomicInteger(),events=new AtomicInteger();
        try(TestServer server=server(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("module-token")){respond(exchange,200,token("t"+tokens.incrementAndGet(),3600));return;}
            events.incrementAndGet();respond(exchange,401,"{}");
        })){
            assertThatThrownBy(()->publisher(server.uri(),Clock.systemUTC()).publish("exemptionRequested","CORE",PAYLOAD))
                .isInstanceOf(CoreHttpPublishException.class).hasMessage("CORE_HTTP_EVENT_AUTHORIZATION_401");
            assertThat(tokens.get()).isEqualTo(2);
            assertThat(events.get()).isEqualTo(2);
        }
    }

    @Test void serverErrorsAndTimeoutsAreRetryable() throws Exception {
        try(TestServer server=server(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("module-token"))respond(exchange,200,token("t",3600));
            else respond(exchange,503,"private server detail");
        })){
            assertRetryable(publisher(server.uri(),Clock.systemUTC()),PAYLOAD);
        }
        try(TestServer server=server(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("module-token")){respond(exchange,200,token("t",3600));return;}
            try{Thread.sleep(500);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
            respond(exchange,202,"{\"eventId\":\""+EVENT_ID+"\",\"accepted\":true}");
        })){
            CoreHttpEventPublisher publisher=new CoreHttpEventPublisher(json,server.uri(),SECRET,Duration.ofMillis(100),
                Duration.ofMillis(100),Duration.ofSeconds(1),Clock.systemUTC());
            assertRetryable(publisher,PAYLOAD);
        }
    }

    @Test void rejectsRemoteHttpAndInvalidEnvelopeBeforeNetworkCall() throws Exception {
        assertThatThrownBy(()->new CoreHttpEventPublisher(json,"http://example.com",SECRET,Duration.ofSeconds(1),
            Duration.ofSeconds(1),Duration.ofSeconds(1),Clock.systemUTC()))
            .isInstanceOf(IllegalStateException.class).hasMessage("CORE_HTTP_BASE_URL_INVALID");
        try(TestServer server=server(exchange->{throw new AssertionError("No HTTP request expected");})){
            assertThatThrownBy(()->publisher(server.uri(),Clock.systemUTC()).publish("wrongType","CORE",PAYLOAD))
                .isInstanceOf(CoreHttpPublishException.class).hasMessage("CORE_HTTP_INVALID_OUTBOX_ENVELOPE");
        }
    }

    private CoreHttpEventPublisher publisher(String uri,Clock clock){return new CoreHttpEventPublisher(json,uri,SECRET,
        Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(1),clock);}

    private static void assertRetryable(CoreHttpEventPublisher publisher,String payload){
        assertThatThrownBy(()->publisher.publish("exemptionRequested","CORE",payload))
            .isInstanceOf(CoreHttpPublishException.class).satisfies(error->
                assertThat(((CoreHttpPublishException)error).retryable()).isTrue());
    }

    private static String token(String value,long expiresIn){return "{\"accessToken\":\""+value+"\",\"expiresIn\":"+expiresIn+",\"module\":\"rentas\",\"tokenType\":\"Bearer\"}";}
    private static void respond(HttpExchange exchange,int status,String body) throws IOException {
        byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);
        try(var output=exchange.getResponseBody()){output.write(bytes);}
    }
    private static TestServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        var executor=Executors.newCachedThreadPool(task->{Thread thread=new Thread(task,"mock-core-http");thread.setDaemon(true);return thread;});
        server.setExecutor(executor);server.createContext("/",handler);server.start();
        return new TestServer("http://127.0.0.1:"+server.getAddress().getPort(),server,executor);
    }
    private record TestServer(String uri,HttpServer server,java.util.concurrent.ExecutorService executor) implements AutoCloseable {
        @Override public void close(){server.stop(0);executor.shutdownNow();}
    }
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private MutableClock(Instant instant){this.instant=new AtomicReference<>(instant);}
        void advance(Duration duration){instant.updateAndGet(value->value.plus(duration));}
        @Override public ZoneId getZone(){return ZoneId.of("UTC");}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return instant.get();}
    }
}
