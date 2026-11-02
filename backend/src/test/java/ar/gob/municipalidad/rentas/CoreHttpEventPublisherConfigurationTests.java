package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** El adaptador HTTP hacia Core no arranca con una URL o una configuración que expongan el secreto o cuelguen hilos. */
class CoreHttpEventPublisherConfigurationTests {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String SECRET="module-secret-for-tests";
    private static final Duration CONNECT=Duration.ofSeconds(3),REQUEST=Duration.ofSeconds(10),SKEW=Duration.ofSeconds(30);

    @ParameterizedTest
    @ValueSource(strings={"https://core.example","https://core.example/","HTTPS://core.example:8443","http://localhost:8089","http://LOCALHOST:8089/","http://127.0.0.1:8089"})
    void acceptsHttpsOrLocalHttpBaseUrl(String baseUrl){
        assertThatCode(()->new CoreHttpEventPublisher(JSON,baseUrl,SECRET,CONNECT,REQUEST,SKEW)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={"core.example","/relative","http://core.example","ftp://core.example","https://user:pw@core.example",
        "https://core.example/api","https://core.example/?tenant=m5","https://core.example/#token","https:///sin-host","::no-es-una-url"})
    void rejectsUnsafeOrAmbiguousBaseUrl(String baseUrl){
        assertThatThrownBy(()->new CoreHttpEventPublisher(JSON,baseUrl,SECRET,CONNECT,REQUEST,SKEW))
            .isInstanceOf(IllegalStateException.class).hasMessage("CORE_HTTP_BASE_URL_INVALID");
    }

    @ParameterizedTest(name="{0}")
    @MethodSource("invalidConfigurations")
    void rejectsMissingSecretOrNonPositiveTimeouts(String scenario,String secret,Duration connect,Duration request,Duration skew){
        assertThatThrownBy(()->new CoreHttpEventPublisher(JSON,"https://core.example",secret,connect,request,skew))
            .isInstanceOf(IllegalStateException.class).hasMessage("CORE_HTTP_CONFIGURATION_REQUIRED");
    }

    @Test void zeroTokenSkewIsAllowed(){
        assertThatCode(()->new CoreHttpEventPublisher(JSON,"https://core.example",SECRET,CONNECT,REQUEST,Duration.ZERO))
            .doesNotThrowAnyException();
    }

    static Stream<Arguments> invalidConfigurations(){
        return Stream.of(
            arguments("sin secreto",null,CONNECT,REQUEST,SKEW),
            arguments("secreto en blanco"," ",CONNECT,REQUEST,SKEW),
            arguments("connect timeout cero",SECRET,Duration.ZERO,REQUEST,SKEW),
            arguments("connect timeout negativo",SECRET,Duration.ofSeconds(-1),REQUEST,SKEW),
            arguments("request timeout cero",SECRET,CONNECT,Duration.ZERO,SKEW),
            arguments("request timeout negativo",SECRET,CONNECT,Duration.ofSeconds(-1),SKEW),
            arguments("token skew negativo",SECRET,CONNECT,REQUEST,Duration.ofSeconds(-1)));
    }
}
