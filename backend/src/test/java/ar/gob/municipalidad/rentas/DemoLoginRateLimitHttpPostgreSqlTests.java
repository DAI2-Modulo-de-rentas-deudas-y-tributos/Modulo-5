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

@SpringBootTest(properties = {
        "rentas.security.trusted-proxy-cidrs=10.0.0.0/8",
        "rentas.security.demo-login-origin-max-failures=20"
})
@AutoConfigureMockMvc
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoLoginRateLimitHttpPostgreSqlTests {
    private static final String REMOTE_ADDRESS = "198.51.100.76";
    private static final String SPRAY_REMOTE_ADDRESS = "198.51.100.77";
    private static final String EXPIRY_REMOTE_ADDRESS = "198.51.100.78";
    private static final String CONCURRENT_REMOTE_ADDRESS = "198.51.100.79";
    private static final String OTHER_REMOTE_ADDRESS = "198.51.100.80";

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

    @Test
    void failedLoginsAcrossUsersShareAnOriginLimitAndIgnoreUntrustedForwardedFor() throws Exception {
        String username = "spray-known-" + UUID.randomUUID();
        var user = demoAuth.create(new DemoAuthController.CreateUserRequest(
                username, "clave-segura", "Spray test", DemoRole.RENTAS, null));

        mvc.perform(login(username, "incorrecta", SPRAY_REMOTE_ADDRESS, "203.0.113.1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(login("spray-missing-" + UUID.randomUUID(), "incorrecta", SPRAY_REMOTE_ADDRESS, "203.0.113.2"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        for (int attempt = 2; attempt < 20; attempt++) {
            mvc.perform(login("spray-missing-" + UUID.randomUUID(), "incorrecta", SPRAY_REMOTE_ADDRESS,
                            "203.0.113." + (attempt + 1)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        String originKey = DemoAuthService.hashToken("login-origin:" + SPRAY_REMOTE_ADDRESS);
        assertThat(jdbc.queryForObject("select failures from demo_login_origin_attempt where origin_key_hash=?",
                Long.class, originKey)).isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from demo_login_origin_attempt where origin_key_hash=?",
                Long.class, DemoAuthService.hashToken("login-origin:203.0.113.20"))).isZero();

        var blockedUnknown = mvc.perform(login("spray-missing-" + UUID.randomUUID(), "incorrecta", SPRAY_REMOTE_ADDRESS,
                        "203.0.113.30"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DEMO_LOGIN_RATE_LIMITED"))
                .andReturn().getResponse();
        var blockedKnown = mvc.perform(login(username, "clave-segura", SPRAY_REMOTE_ADDRESS, "203.0.113.31"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DEMO_LOGIN_RATE_LIMITED"))
                .andReturn().getResponse();
        assertThat(blockedUnknown.getHeader("Retry-After")).isEqualTo("900");
        assertThat(blockedKnown.getHeader("Retry-After")).isEqualTo("900");
        assertThat(json.readTree(blockedKnown.getContentAsByteArray()).path("message").asText())
                .isEqualTo(json.readTree(blockedUnknown.getContentAsByteArray()).path("message").asText());
        assertThat(jdbc.queryForObject("select count(*) from demo_auth_session where demo_user_id=?",
                Long.class, user.id())).isZero();

        mvc.perform(login(username, "clave-segura", OTHER_REMOTE_ADDRESS, "203.0.113.30"))
                .andExpect(status().isOk());
    }

    @Test
    void originWindowExpiresAfterFifteenMinutes() throws Exception {
        String username = "spray-expiry-" + UUID.randomUUID();
        demoAuth.create(new DemoAuthController.CreateUserRequest(
                username, "clave-segura", "Spray expiry", DemoRole.RENTAS, null));
        for (int attempt = 0; attempt < 20; attempt++) {
            mvc.perform(login("spray-expired-" + UUID.randomUUID(), "incorrecta", EXPIRY_REMOTE_ADDRESS, null))
                    .andExpect(status().isUnauthorized());
        }
        String originKey = DemoAuthService.hashToken("login-origin:" + EXPIRY_REMOTE_ADDRESS);
        assertThat(jdbc.queryForObject("select failures from demo_login_origin_attempt where origin_key_hash=?",
                Long.class, originKey)).isEqualTo(20);
        jdbc.update("update demo_login_origin_attempt set window_started_at=CURRENT_TIMESTAMP - INTERVAL '16 minutes' where origin_key_hash=?",
                originKey);

        mvc.perform(login(username, "clave-segura", EXPIRY_REMOTE_ADDRESS, null))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from demo_login_origin_attempt where origin_key_hash=?",
                Long.class, originKey)).isZero();
    }

    @Test
    void concurrentFailuresFromDifferentUsersAdmitAtMostTwentyPerOrigin() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var attempts = java.util.stream.IntStream.range(0, 24)
                    .<java.util.concurrent.Callable<String>>mapToObj(index -> () -> {
                        try {
                            demoAuth.login(new DemoAuthController.LoginRequest("spray-concurrent-" + index, "incorrecta"),
                                    CONCURRENT_REMOTE_ADDRESS);
                            return "UNEXPECTED_SUCCESS";
                        } catch (BusinessException failure) {
                            return failure.code;
                        }
                    }).toList();
            var outcomes = pool.invokeAll(attempts).stream()
                    .map(future -> {
                        try { return future.get(); }
                        catch (Exception failure) { throw new RuntimeException(failure); }
                    }).toList();
            assertThat(outcomes.stream().filter("INVALID_CREDENTIALS"::equals).count()).isEqualTo(20);
            assertThat(outcomes.stream().filter("DEMO_LOGIN_RATE_LIMITED"::equals).count()).isEqualTo(4);
            assertThat(outcomes).doesNotContain("UNEXPECTED_SUCCESS");
        } finally {
            pool.shutdownNow();
        }
        String originKey = DemoAuthService.hashToken("login-origin:" + CONCURRENT_REMOTE_ADDRESS);
        assertThat(jdbc.queryForObject("select failures from demo_login_origin_attempt where origin_key_hash=?",
                Long.class, originKey)).isEqualTo(21);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            String username, String password, String remoteAddress, String forwardedFor) throws Exception {
        var request = post("/api/v1/dev-auth/login")
                .with(servletRequest -> {
                    servletRequest.setRemoteAddr(remoteAddress);
                    return servletRequest;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new DemoAuthController.LoginRequest(username, password)));
        if (forwardedFor != null) request.header("X-Forwarded-For", forwardedFor);
        return request;
    }
}
