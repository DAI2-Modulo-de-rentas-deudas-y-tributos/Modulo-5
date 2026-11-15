package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SocialBenefitRuleIdempotencyPostgreSqlTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/migration-postgresql,classpath:db/migration-demo");
    }

    @Autowired MockMvc mvc;
    @Autowired CatalogService catalog;
    @Autowired SocialBenefitRuleRepository rules;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void authenticateFixtureSetup() {
        SecurityContextHolder.getContext().setAuthentication(rentas("social-benefit-setup-" + UUID.randomUUID()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void proposalReplayAndTransitionReplayReturnStoredResponses() throws Exception {
        TaxConcept concept = catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
            "SBR_IDEMP_" + UUID.randomUUID(), "Social benefit idempotency", null, TaxConceptType.FEE, "M5"));
        UUID programId = UUID.randomUUID();
        String actor = "social-benefit-http-" + UUID.randomUUID();
        var auth = rentas(actor);
        String key = "social-benefit-" + UUID.randomUUID();
        ApiDtos.CreateSocialBenefitRuleRequest request = new ApiDtos.CreateSocialBenefitRuleRequest(
            programId, concept.code, new BigDecimal("25.00"), LocalDate.of(2026, 1, 1), null);
        String body = json.writeValueAsString(request);

        String first = mvc.perform(post("/api/v1/social-benefit-rules").with(authentication(auth))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/v1/social-benefit-rules").with(authentication(auth))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        JsonNode firstJson = json.readTree(first);
        assertThat(replay).isEqualTo(first);
        long ruleId = firstJson.path("id").asLong();
        assertThat(rules.findByProgramIdAndTaxConceptId(programId, concept.id)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where actor_id=? and operation=? and idempotency_key=?",
            Long.class, actor, "POST:/social-benefit-rules", key)).isOne();

        var changed = new ApiDtos.CreateSocialBenefitRuleRequest(
            programId, concept.code, new BigDecimal("30.00"), LocalDate.of(2026, 1, 1), null);
        mvc.perform(post("/api/v1/social-benefit-rules").with(authentication(auth))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(changed)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(rules.findByProgramIdAndTaxConceptId(programId, concept.id)).hasSize(1);

        String submitKey = "social-benefit-submit-" + UUID.randomUUID();
        String submitted = mvc.perform(post("/api/v1/social-benefit-rules/{id}/submit", ruleId)
                .with(authentication(auth)).header("Idempotency-Key", submitKey))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String submittedReplay = mvc.perform(post("/api/v1/social-benefit-rules/{id}/submit", ruleId)
                .with(authentication(auth)).header("Idempotency-Key", submitKey))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(submittedReplay).isEqualTo(submitted);
        assertThat(rules.findById(ruleId).orElseThrow().status).isEqualTo(SocialBenefitRuleStatus.PENDING_APPROVAL);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where actor_id=? and operation=? and idempotency_key=?",
            Long.class, actor, "POST:/social-benefit-rules/{id}/submit:" + ruleId, submitKey)).isOne();
    }

    private static UsernamePasswordAuthenticationToken rentas(String actor) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(actor, null), null,
            List.of(new SimpleGrantedAuthority("ROLE_RENTAS")));
    }

}
