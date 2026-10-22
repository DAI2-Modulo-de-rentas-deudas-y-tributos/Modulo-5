package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.context.jdbc.Sql(statements="INSERT INTO demo_bootstrap_lock(id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM demo_bootstrap_lock WHERE id=1)")
class SecurityErrorContractTests {
    @Autowired MockMvc mvc;
    @Autowired DemoUserRepository users;
    @Autowired DemoAuthSessionRepository sessions;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService auth;
    @Autowired ObjectMapper json;

    private DemoAuthSessions demo;

    @BeforeEach void setUp() {
        demo=new DemoAuthSessions(users,taxpayers,encoder,auth);
    }

    @Test void missingAuthenticationUsesTheStandardContractAndCorrelationId() throws Exception {
        assertSecurityError(mvc.perform(get("/api/v1/tax-concepts")
                .header("X-Correlation-Id","missing-auth-trace")),401,"UNAUTHENTICATED","missing-auth-trace");
    }

    @Test void invalidSessionUsesTheStandardContractWithoutEchoingTheToken() throws Exception {
        var result=assertSecurityError(mvc.perform(get("/api/v1/tax-concepts")
                .header("X-Demo-Session","secret-invalid-session-token")
                .header("X-Correlation-Id","invalid-session-trace")),401,"UNAUTHENTICATED","invalid-session-trace");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-invalid-session-token");
    }

    @Test void expiredSessionUsesTheStandardContract() throws Exception {
        String token=demo.token(DemoRole.RENTAS);
        DemoAuthSession session=sessions.findByTokenHash(DemoAuthService.hashToken(token)).orElseThrow();
        session.expiresAt=OffsetDateTime.now().minusSeconds(1);
        sessions.saveAndFlush(session);

        assertSecurityError(mvc.perform(get("/api/v1/tax-concepts")
                .header("X-Demo-Session",token)
                .header("X-Correlation-Id","expired-session-trace")),401,"UNAUTHENTICATED","expired-session-trace");
    }

    @Test void authenticatedUserWithoutPermissionReceivesAStandard403() throws Exception {
        assertSecurityError(mvc.perform(post("/api/v1/tax-concepts")
                .header("X-Demo-Session",demo.token(DemoRole.AUDITOR))
                .header("X-Correlation-Id","forbidden-trace")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"QA\",\"name\":\"QA\",\"type\":\"FEE\",\"originModule\":\"M5\"}")),
                403,"ACCESS_DENIED","forbidden-trace");
    }

    @Test void malformedJsonRetainsTheStandard400Contract() throws Exception {
        mvc.perform(post("/api/v1/tax-concepts")
                .header("X-Demo-Session",demo.token(DemoRole.RENTAS))
                .header("X-Correlation-Id","malformed-json-trace")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.path").value("/api/v1/tax-concepts"))
            .andExpect(jsonPath("$.details").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.traceId").value("malformed-json-trace"))
            .andExpect(header().string("X-Correlation-Id","malformed-json-trace"));
    }

    @Test void openApiDocuments401And403WithTheSharedErrorSchema() throws Exception {
        var document=json.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var paths=document.path("paths");
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/tax-concepts").path("get")
            .path("responses").path("401").path("$ref").asText())
            .isEqualTo("#/components/responses/ApiError");
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/tax-concepts").path("post")
            .path("responses").path("403").path("$ref").asText())
            .isEqualTo("#/components/responses/ApiError");
        var schema=document.path("components").path("schemas").path("ErrorResponse").path("properties");
        for(String field:new String[]{"timestamp","status","code","message","path","traceId","details"})
            org.assertj.core.api.Assertions.assertThat(schema.has(field)).as("campo %s",field).isTrue();
    }

    private org.springframework.test.web.servlet.MvcResult assertSecurityError(
            org.springframework.test.web.servlet.ResultActions result,int statusCode,
            String code,String traceId) throws Exception {
        return result.andExpect(status().is(statusCode))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.status").value(statusCode))
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.path").exists())
            .andExpect(jsonPath("$.traceId").value(traceId))
            .andExpect(jsonPath("$.details").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(header().string("X-Correlation-Id",traceId)).andReturn();
    }
}
