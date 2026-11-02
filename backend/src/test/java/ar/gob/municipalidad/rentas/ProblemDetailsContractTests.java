package ar.gob.municipalidad.rentas;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailsContractTests {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 412, 422, 429})
    void businessErrorsKeepProblemDetailsAndLegacyExtensions(int status) {
        MDC.put("traceId", "problem-contract-test");
        try {
            HttpServletRequest request = request();
            var exception = status==429
                ?new BusinessException("CONTRACT_TEST","Mensaje funcional",status,900)
                :new BusinessException("CONTRACT_TEST","Mensaje funcional",status);
            var response = handler.business(exception, request);

            assertThat(response.getStatusCode().value()).isEqualTo(status);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            if(status==429)assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("900");
            assertThat(response.getBody()).satisfies(body -> {
                assertThat(body.type()).isEqualTo("about:blank");
                assertThat(body.title()).isEqualTo(org.springframework.http.HttpStatus.valueOf(status).getReasonPhrase());
                assertThat(body.status()).isEqualTo(status);
                assertThat(body.detail()).isEqualTo("Mensaje funcional");
                assertThat(body.instance()).isEqualTo("/api/v1/debts/15");
                assertThat(body.message()).isEqualTo(body.detail());
                assertThat(body.path()).isEqualTo(body.instance());
                assertThat(body.code()).isEqualTo("CONTRACT_TEST");
                assertThat(body.traceId()).isEqualTo("problem-contract-test");
            });
        } finally {
            MDC.remove("traceId");
        }
    }

    @org.junit.jupiter.api.Test
    void unexpectedErrorDoesNotExposeTechnicalDetails() {
        var response = handler.unexpected(new IllegalStateException("secret SQL detail"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).satisfies(body -> {
            assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
            assertThat(body.detail()).isEqualTo("Ocurrió un error interno");
            assertThat(body.type()).isEqualTo("about:blank");
            assertThat(body.status()).isEqualTo(500);
            assertThat(body.message()).doesNotContain("secret SQL detail", "IllegalStateException");
        });
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/v1/debts/15");
    }
}
