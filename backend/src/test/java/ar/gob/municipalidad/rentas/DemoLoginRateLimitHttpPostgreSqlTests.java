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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "rentas.security.trusted-proxy-cidrs=10.0.0.0/8")
@AutoConfigureMockMvc
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoLoginRateLimitHttpPostgreSqlTests {
    private static final String REMOTE_ADDRESS = "198.51.100.76";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/migration-postgresql,classpath:db/migration-demo");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoAuthService demoAuth;

    @BeforeEach
    void authenticateFixtureSetup() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_SUPERVISOR"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedIdentity("scrum-788-http-test", null), null, authorities));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validPasswordAtHttpBoundaryRemainsRateLimitedWithoutIssuingSession() throws Exception {
        String username = "rate-http-" + UUID.randomUUID();
        var user = demoAuth.create(new DemoAuthController.CreateUserRequest(
                username, "clave-segura", "Rate limit HTTP", DemoRole.RENTAS, null));

        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(post("/api/v1/dev-auth/login")
                            .with(request -> {
                                request.setRemoteAddr(REMOTE_ADDRESS);
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(new DemoAuthController.LoginRequest(username, "incorrecta"))))
                    .andExpect(status().isUnauthorized());
        }

        var response = mvc.perform(post("/api/v1/dev-auth/login")
                        .with(request -> {
                            request.setRemoteAddr(REMOTE_ADDRESS);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new DemoAuthController.LoginRequest(username, "clave-segura"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DEMO_LOGIN_RATE_LIMITED"))
                .andReturn()
                .getResponse();

        assertThat(response.getHeader("Retry-After")).isEqualTo("900");
        JsonNode error = json.readTree(response.getContentAsByteArray());
        assertThat(error.has("token")).isFalse();
        assertThat(error.has("session")).isFalse();
        assertThat(jdbc.queryForObject(
                "select count(*) from demo_auth_session where demo_user_id = ?", Long.class, user.id())).isZero();
    }
}
