package ar.gob.municipalidad.rentas;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"rentas.citizen-auth.enabled=true", "rentas.citizen-auth.timeout-ms=1000",
        "rentas.security.trusted-proxy-cidrs=192.0.2.0/24"})
@AutoConfigureMockMvc
@ActiveProfiles({"postgres-it", "citizen-auth-local"})
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CitizenSessionPostgreSqlTest {
    private static final UUID CITIZEN = UUID.randomUUID();
    private static final AtomicInteger CONCURRENT_CALLS = new AtomicInteger();
    private static final HttpServer M1 = startM1();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine");

    @Autowired MockMvc mvc;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConfirmedM1Consumer m1;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/migration-postgresql,classpath:db/migration-demo");
        registry.add("rentas.citizen-auth.base-url", () -> "http://127.0.0.1:" + M1.getAddress().getPort());
    }

    @AfterAll
    static void stopM1() {
        M1.stop(0);
    }

    @Test
    void sessionRequiresCsrfAndOwnershipAndCanBeRevoked() throws Exception {
        var owner = taxpayer(CITIZEN);
        var unrelated = taxpayer(UUID.randomUUID());
        String path = "/api/v1/taxpayers/";
        mvc.perform(login("ok", 1).header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/citizen-auth/login")).andExpect(status().isForbidden());
        Cookie cookie = authenticate(1);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM citizen_session WHERE taxpayer_id=?", String.class, owner.id))
                .isEqualTo(DemoAuthService.hashToken(cookie.getValue()));
        mvc.perform(get(path + owner.id + "/debts").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get(path + unrelated.id + "/debts").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/taxpayers").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/citizen-auth/me").header("Authorization", "Bearer synthetic"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/citizen-auth/logout").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/exemption-requests").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/citizen-auth/logout").cookie(cookie).header("X-Rentas-CSRF", "1"))
                .andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        mvc.perform(get("/api/v1/citizen-auth/me").cookie(cookie)).andExpect(status().isUnauthorized());

        Cookie expired = authenticate(1);
        jdbc.update("UPDATE citizen_session SET expires_at=CURRENT_TIMESTAMP WHERE taxpayer_id=?", owner.id);
        mvc.perform(get("/api/v1/citizen-auth/me").cookie(expired)).andExpect(status().isUnauthorized());

        Cookie unlinked = authenticate(1);
        jdbc.update("UPDATE taxpayer_reference SET canonical_citizen_id=NULL WHERE id=?", owner.id);
        mvc.perform(get("/api/v1/citizen-auth/me").cookie(unlinked)).andExpect(status().isUnauthorized());

        jdbc.update("UPDATE taxpayer_reference SET canonical_citizen_id=? WHERE id=?", CITIZEN, owner.id);
        Cookie blocked = authenticate(1);
        citizenUpdate("BLOCKED", """
                {"reason":"DOCUMENTACION_APOCRIFA","reviewRequired":true}
                """);
        mvc.perform(get("/api/v1/citizen-auth/me").cookie(blocked)).andExpect(status().isUnauthorized());
        citizenUpdate("UPDATED", """
                {"dni":"99900042","cuil":"20999000429","name":"Synthetic","lastname":"Citizen",
                 "birthdate":"1990-01-01","state":"ACTIVO"}
                """);
        mvc.perform(get("/api/v1/citizen-auth/me").cookie(blocked)).andExpect(status().isUnauthorized());
    }

    private void citizenUpdate(String type, String details) throws Exception {
        m1.consumeCitizen(new ConfirmedInboundEvents.M1CoreEnvelope<>(UUID.randomUUID(), "citizenUpdated",
                "1.0", OffsetDateTime.now(), "ciudadanos", UUID.randomUUID(), null,
                new ConfirmedInboundEvents.M1CitizenUpdatedData(CITIZEN, type, json.readTree(details),
                        new ConfirmedInboundEvents.UpdatedBy("AREA_USER", "synthetic"))));
    }

    @Test
    void upstreamErrorsAndUnlinkedCitizensYieldNoSession() throws Exception {
        String[] cases = {"denied", "employee", "absent", "contradiction", "invalidUuid", "invalid", "limited", "down", "timeout"};
        int[] expected = {401, 401, 401, 502, 502, 502, 429, 503, 503};
        for (int i = 0; i < cases.length; i++) {
            mvc.perform(login(cases[i], 20 + i)).andExpect(status().is(expected[i]))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
    }

    @Test
    void concurrentRequestsCannotExceedTheOriginBudget() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> (java.util.concurrent.Callable<Integer>) () -> mvc.perform(login("concurrent", 40))
                            .andReturn().getResponse().getStatus())
                    .toList();
            var results = executor.invokeAll(tasks);
            int limited = 0;
            for (var result : results) {
                if (result.get() == 429) limited++;
                else assertThat(result.get()).isEqualTo(401);
            }
            assertThat(limited).isEqualTo(4);
            assertThat(CONCURRENT_CALLS.get()).isEqualTo(4);
            mvc.perform(login("concurrent", 40)).andExpect(status().isTooManyRequests());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rateLimitUsesTrustedProxyAddressAndIgnoresSpoofedForwardedHeader() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            mvc.perform(loginFrom("denied", "192.0.2.240", "198.51.100.90"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(loginFrom("denied", "192.0.2.240", "198.51.100.91"))
                .andExpect(status().isUnauthorized());

        for (int attempt = 0; attempt < 4; attempt++) {
            mvc.perform(loginFrom("denied", "203.0.113.240", "198.51.100." + attempt))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(loginFrom("denied", "203.0.113.240", "198.51.100.99"))
                .andExpect(status().isTooManyRequests());
    }

    private Cookie authenticate(int origin) throws Exception {
        var response = mvc.perform(login("ok", origin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TAXPAYER"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=600")))
                .andReturn().getResponse();
        return response.getCookie(CitizenAuthController.COOKIE);
    }

    private static MockHttpServletRequestBuilder login(String username, int origin) {
        return loginFrom(username, "192.0.2." + origin, null);
    }

    private static MockHttpServletRequestBuilder loginFrom(String username, String peer, String forwardedFor) {
        var request = post("/api/v1/citizen-auth/login").header("X-Rentas-CSRF", "1")
                .contentType("application/json").content("""
                        {"username":"%s","password":"synthetic-password"}
                        """.formatted(username)).with(r -> {
                            r.setRemoteAddr(peer);
                            return r;
                        });
        return forwardedFor == null ? request : request.header("X-Forwarded-For", forwardedFor);
    }

    private TaxpayerReference taxpayer(UUID uuid) {
        var t = new TaxpayerReference();
        t.taxpayerType = TaxpayerType.CITIZEN;
        t.externalId = uuid.toString();
        t.canonicalCitizenId = uuid;
        t.displayName = "Synthetic citizen";
        t.externalStatus = TaxpayerStatus.ACTIVE;
        t.createdAt = t.updatedAt = OffsetDateTime.now();
        return taxpayers.save(t);
    }

    private static HttpServer startM1() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/auth/usuario/login", exchange -> {
                try (exchange) {
                    var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody());
                    if (exchange.getRequestHeaders().containsKey("X-Forwarded-For")
                            || !"synthetic-password".equals(body.path("password").asText())) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                    String username = body.path("gmail").asText();
                    if ("concurrent".equals(username)) CONCURRENT_CALLS.incrementAndGet();
                    if ("timeout".equals(username)) {
                        try {
                            Thread.sleep(1_500);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    int status = switch (username) {
                        case "denied", "concurrent" -> 401;
                        case "limited" -> 429;
                        case "down" -> 503;
                        default -> 200;
                    };
                    String actor = "employee".equals(username) ? "EMPLEADO" : "CIUDADANO";
                    String citizen = switch (username) {
                        case "absent" -> UUID.randomUUID().toString();
                        case "invalidUuid" -> "1-1-1-1-1";
                        default -> CITIZEN.toString();
                    };
                    String actorId = "contradiction".equals(username) ? UUID.randomUUID().toString() : citizen;
                    byte[] response = ("invalid".equals(username) ? "{!" : """
                            {"auth":true,"actorType":"%s","role":"%s","uuid":"%s","actorId":"%s","citizenId":"%s"}
                            """.formatted(actor, actor, citizen, actorId, citizen)).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
