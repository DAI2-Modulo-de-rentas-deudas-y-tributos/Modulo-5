package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.filter.CorsFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(properties = {
    "rentas.security.dev-mode=false",
    "rentas.cors.allowed-origins=https://dev.example.com, https://test.example.com",
    "spring.datasource.url=jdbc:h2:mem:cors;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class CorsConfigurationTest {
    private static final String ORIGIN = "https://dev.example.com";
    @Autowired MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void allowedPreflightDoesNotRequireAuthentication(String method) throws Exception {
        mvc.perform(options("/api/v1/taxpayers")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                    "content-type,accept,authorization,x-dev-user,x-dev-roles,x-dev-taxpayer-id,x-correlation-id"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString(method)))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("x-dev-taxpayer-id")))
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://unknown.example.com", "https://dev.example.com.attacker.invalid", "null"})
    void unlistedOriginCannotPreflightOrReadAnApiResponse(String origin) throws Exception {
        mvc.perform(options("/api/v1/taxpayers")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mvc.perform(get("/api/v1/health").header(HttpHeaders.ORIGIN, origin))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test void publicApiResponseExposesDownloadAndCorrelationHeaders() throws Exception {
        mvc.perform(get("/api/v1/health")
                .header(HttpHeaders.ORIGIN, "https://test.example.com")
                .header("X-Correlation-Id", "cors-health-test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://test.example.com"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Content-Disposition")))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("X-Correlation-Id")))
            .andExpect(header().string("X-Correlation-Id", "cors-health-test"));
    }

    @Test void allowedOriginDoesNotBypassAuthenticationAndCanReadTheError() throws Exception {
        mvc.perform(get("/api/v1/taxpayers").header(HttpHeaders.ORIGIN, ORIGIN))
            .andExpect(status().isForbidden())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
            .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test void applicationErrorsKeepCorsAndCorrelationHeaders() throws Exception {
        mvc.perform(get("/api/v1/taxpayers/not-a-number")
                .with(user("cors-test").roles("RENTAS"))
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header("X-Correlation-Id", "cors-error-test"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
            .andExpect(header().string("X-Correlation-Id", "cors-error-test"));
    }

    @Test void sameOriginRequestsKeepWorkingWithoutCorsHeaders() throws Exception {
        mvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test void preflightOutsideTheApiIsNotEnabled() throws Exception {
        mvc.perform(options("/actuator/health")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test void emptyConfigurationRejectsCrossOriginRequests() throws Exception {
        var filter = new CorsFilter(new SecurityConfig().corsConfigurationSource(""));
        var request = new MockHttpServletRequest("OPTIONS", "/api/v1/taxpayers");
        request.addHeader(HttpHeaders.ORIGIN, ORIGIN);
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.com", "null"})
    void wildcardAndOpaqueOriginsAreNotAcceptedInConfiguration(String origin) {
        assertThatThrownBy(() -> new SecurityConfig().corsConfigurationSource(origin))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CORS_ALLOWED_ORIGINS");
    }
}
