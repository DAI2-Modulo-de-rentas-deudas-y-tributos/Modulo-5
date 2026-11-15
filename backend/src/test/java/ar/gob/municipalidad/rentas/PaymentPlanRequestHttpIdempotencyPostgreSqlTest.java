package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker = true)
class PaymentPlanRequestHttpIdempotencyPostgreSqlTest {
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
    @Autowired LiquidationService liquidations;
    @Autowired PlanWorkflowService plans;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired DebtRepository debts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void concurrentHttpReplayReturnsTheSameRequestAndRejectsChangedBody() throws Exception {
        var rentas = identity("plan-idempotency-setup-" + UUID.randomUUID(), null, "RENTAS");
        SecurityContextHolder.getContext().setAuthentication(rentas);

        TaxpayerReference taxpayer = catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
            TaxpayerType.CITIZEN, "plan-idempotency-" + UUID.randomUUID(), "12345678", null, "Synthetic plan test"));
        taxpayer.canonicalCitizenId = UUID.randomUUID();
        taxpayers.saveAndFlush(taxpayer);
        TaxConcept concept = catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
            "PLAN_IDEMP_" + UUID.randomUUID(), "Plan idempotency", null, TaxConceptType.FEE, "M5"));
        TaxConfiguration taxConfiguration = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
            concept.id, CalculationType.FIXED, null, new BigDecimal("1200.00"), null, null,
            true, true, LocalDate.now().minusDays(1), null));
        catalog.submit(taxConfiguration.id);
        TestActorSupport.asApprover(() -> catalog.approve(taxConfiguration.id));
        Liquidation liquidation = liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id, concept.id,
            YearMonth.now().plusMonths(1).toString(), BigDecimal.ZERO, LocalDate.now().plusDays(30)));
        Debt debt = debts.findByLiquidationId(liquidation.id).orElseThrow();

        plans.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(
            1, 12, BigDecimal.ZERO, BigDecimal.ZERO, 0, 2, true, true, 1,
            LocalDate.now().minusDays(1), null, true));

        var request = new ApiDtos.CreatePaymentPlanRequest(taxpayer.id, List.of(debt.id), 3);
        String body = json.writeValueAsString(request);
        String key = "plan-request-" + UUID.randomUUID();
        var taxpayerAuth = identity("plan-idempotency-user-" + UUID.randomUUID(), taxpayer.id, "TAXPAYER");

        List<org.springframework.test.web.servlet.MvcResult> responses = runTogether(
            () -> mvc.perform(post("/api/v1/payment-plan-requests").with(authentication(taxpayerAuth))
                    .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn(),
            () -> mvc.perform(post("/api/v1/payment-plan-requests").with(authentication(taxpayerAuth))
                    .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn());

        String firstBody = responses.get(0).getResponse().getContentAsString();
        String replayBody = responses.get(1).getResponse().getContentAsString();
        assertThat(responses).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));
        assertThat(replayBody).isEqualTo(firstBody);
        JsonNode stored = json.readTree(firstBody);
        long requestId = stored.path("id").asLong();
        assertThat(requestId).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from payment_plan_request where taxpayer_id=?", Long.class, taxpayer.id)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where operation=? and idempotency_key=?",
            Long.class, "POST:/payment-plan-requests", key)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type='paymentPlanRequested' and aggregate_type='PaymentPlanRequest' and aggregate_id=?",
            Long.class, stored.path("id").asText())).isOne();

        var changed = new ApiDtos.CreatePaymentPlanRequest(taxpayer.id, List.of(debt.id), 4);
        mvc.perform(post("/api/v1/payment-plan-requests").with(authentication(taxpayerAuth))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(changed)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select count(*) from payment_plan_request where taxpayer_id=?", Long.class, taxpayer.id)).isOne();
    }

    private static UsernamePasswordAuthenticationToken identity(String actor, Long taxpayerId, String role) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(actor, taxpayerId), null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static List<org.springframework.test.web.servlet.MvcResult> runTogether(Callable<org.springframework.test.web.servlet.MvcResult> first,
            Callable<org.springframework.test.web.servlet.MvcResult> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<org.springframework.test.web.servlet.MvcResult>> futures = List.of(
                pool.submit(task(first, ready, start)), pool.submit(task(second, ready, start)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<org.springframework.test.web.servlet.MvcResult> task(Callable<org.springframework.test.web.servlet.MvcResult> action,
            CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try { return action.call(); }
            finally { SecurityContextHolder.clearContext(); }
        };
    }
}
