package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Clasificación de las respuestas de Core: lo que puede resolverse reintentando vuelve como
 * reintentable; lo que nunca va a aceptarse termina en DLQ.
 */
class CoreHttpEventPublisherResponseTests {
    private static final String SECRET="local-test-secret";
    private static final String EVENT_ID="fc444493-302a-4f30-a2a1-9360eaa26ed7";
    private static final String PAYLOAD="{\"eventId\":\""+EVENT_ID+"\",\"eventType\":\"exemptionRequested\",\"eventVersion\":\"1.0\","
        +"\"occurredAt\":\"2026-10-31T12:30:00-03:00\",\"sourceModule\":\"rentas\",\"correlationId\":\"ec94049c-0fc7-4d80-b8cf-0b8f6a69dc4a\","
        +"\"causationId\":null,\"data\":{\"requestId\":\"synthetic\"}}";
    private static final String VALID_TOKEN=token("t","3600","Bearer","rentas");
    private final ObjectMapper json=new ObjectMapper();

    @ParameterizedTest(name="token HTTP {0}")
    @MethodSource("tokenStatuses")
    void classifiesTokenEndpointStatus(int status,String code,boolean retryable) throws Exception {
        try(TestServer server=server(exchange->respond(exchange,status,"private server detail"))){
            assertFailure(publisher(server.uri()),"CORE",PAYLOAD,code,retryable);
        }
    }

    static Stream<Arguments> tokenStatuses(){
        return Stream.of(
            arguments(401,"CORE_HTTP_TOKEN_AUTHORIZATION_401",false),
            arguments(403,"CORE_HTTP_TOKEN_AUTHORIZATION_403",false),
            arguments(422,"CORE_HTTP_TOKEN_CONFIGURATION_REJECTED",false),
            arguments(408,"CORE_HTTP_TOKEN_RETRYABLE_408",true),
            arguments(429,"CORE_HTTP_TOKEN_RETRYABLE_429",true),
            arguments(500,"CORE_HTTP_TOKEN_RETRYABLE_500",true),
            arguments(404,"CORE_HTTP_TOKEN_REJECTED_404",false));
    }

    @ParameterizedTest(name="{0}")
    @MethodSource("tokenBodies")
    void rejectsInvalidTokenBody(String scenario,String body,boolean retryable) throws Exception {
        try(TestServer server=server(exchange->respond(exchange,200,body))){
            assertFailure(publisher(server.uri()),"CORE",PAYLOAD,"CORE_HTTP_TOKEN_RESPONSE_INVALID",retryable);
        }
    }

    static Stream<Arguments> tokenBodies(){
        return Stream.of(
            arguments("token vacío",token("","3600","Bearer","rentas"),false),
            arguments("vencimiento cero",token("t","0","Bearer","rentas"),false),
            arguments("tipo distinto de Bearer",token("t","3600","Basic","rentas"),false),
            arguments("token de otro módulo",token("t","3600","Bearer","transito"),false),
            arguments("cuerpo que no es JSON","no-json",true));
    }

    @ParameterizedTest(name="evento HTTP {0}")
    @MethodSource("eventResponses")
    void classifiesEventEndpointResponse(int status,String body,String code,boolean retryable) throws Exception {
        try(TestServer server=server(exchange->{
            if(isToken(exchange))respond(exchange,200,VALID_TOKEN);
            else respond(exchange,status,body);
        })){
            assertFailure(publisher(server.uri()),"CORE",PAYLOAD,code,retryable);
        }
    }

    static Stream<Arguments> eventResponses(){
        return Stream.of(
            arguments(408,"{}","CORE_HTTP_EVENT_RETRYABLE_408",true),
            arguments(429,"{}","CORE_HTTP_EVENT_RETRYABLE_429",true),
            arguments(400,"{}","CORE_HTTP_EVENT_REJECTED_400",false),
            arguments(202,"{\"eventId\":\""+EVENT_ID+"\",\"accepted\":false}","CORE_HTTP_UNEXPECTED_RESPONSE_202",false),
            arguments(200,"{\"eventId\":\""+EVENT_ID+"\",\"duplicate\":false}","CORE_HTTP_UNEXPECTED_RESPONSE_200",false),
            arguments(202,"{\"eventId\":\"0b9ab1c6-63f4-4d64-8a19-4ab0d3e52b60\",\"accepted\":true}","CORE_HTTP_RESPONSE_EVENT_ID_MISMATCH",false),
            arguments(202,"{\"accepted\":true}","CORE_HTTP_RESPONSE_INVALID",true),
            arguments(202,"no-json","CORE_HTTP_RESPONSE_INVALID",true));
    }

    @Test void reusesCachedTokenWhileItIsValid() throws Exception {
        AtomicInteger tokens=new AtomicInteger();
        try(TestServer server=server(exchange->{
            if(isToken(exchange)){tokens.incrementAndGet();respond(exchange,200,VALID_TOKEN);}
            else respond(exchange,202,"{\"eventId\":\""+EVENT_ID+"\",\"accepted\":true}");
        })){
            CoreHttpEventPublisher publisher=publisher(server.uri());
            assertThat(publisher.publish("exemptionRequested","CORE",PAYLOAD)).isEqualTo(OutboxStatus.CORE_ACCEPTED);
            assertThat(publisher.publish("exemptionRequested","CORE",PAYLOAD)).isEqualTo(OutboxStatus.CORE_ACCEPTED);
            assertThat(tokens.get()).isEqualTo(1);
        }
    }

    @Test void onlyCoreIsASupportedTarget() throws Exception {
        try(TestServer server=server(exchange->{throw new AssertionError("No HTTP request expected");})){
            assertFailure(publisher(server.uri()),"M7",PAYLOAD,"CORE_HTTP_UNSUPPORTED_OUTBOX_TARGET",false);
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"\"eventVersion\":\"1.0\"=>\"eventVersion\":\"2.0\"","\"sourceModule\":\"rentas\"=>\"sourceModule\":\"tributos\"",
        "\"data\":{\"requestId\":\"synthetic\"}=>\"data\":[]"})
    void rejectsEnvelopeOutsideTheAgreedContract(String replacement) throws Exception {
        String[] parts=replacement.split("=>");
        try(TestServer server=server(exchange->{throw new AssertionError("No HTTP request expected");})){
            assertFailure(publisher(server.uri()),"CORE",PAYLOAD.replace(parts[0],parts[1]),"CORE_HTTP_INVALID_OUTBOX_ENVELOPE",false);
        }
    }

    private CoreHttpEventPublisher publisher(String uri){
        return new CoreHttpEventPublisher(json,uri,SECRET,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(1),Clock.systemUTC());
    }
    private static void assertFailure(CoreHttpEventPublisher publisher,String target,String payload,String code,boolean retryable){
        assertThatThrownBy(()->publisher.publish("exemptionRequested",target,payload))
            .isInstanceOf(CoreHttpPublishException.class).hasMessage(code)
            .satisfies(error->assertThat(((CoreHttpPublishException)error).retryable()).isEqualTo(retryable));
    }
    private static boolean isToken(HttpExchange exchange){return exchange.getRequestURI().getPath().endsWith("module-token");}
    private static String token(String value,String expiresIn,String type,String module){
        return "{\"accessToken\":\""+value+"\",\"expiresIn\":"+expiresIn+",\"module\":\""+module+"\",\"tokenType\":\""+type+"\"}";
    }
    private static void respond(HttpExchange exchange,int status,String body) throws IOException {
        byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);
        try(var output=exchange.getResponseBody()){output.write(bytes);}
    }
    private static TestServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        ExecutorService executor=Executors.newCachedThreadPool(task->{Thread thread=new Thread(task,"mock-core-http");thread.setDaemon(true);return thread;});
        server.setExecutor(executor);server.createContext("/",handler);server.start();
        return new TestServer("http://127.0.0.1:"+server.getAddress().getPort(),server,executor);
    }
    private record TestServer(String uri,HttpServer server,ExecutorService executor) implements AutoCloseable {
        @Override public void close(){server.stop(0);executor.shutdownNow();}
    }
}
