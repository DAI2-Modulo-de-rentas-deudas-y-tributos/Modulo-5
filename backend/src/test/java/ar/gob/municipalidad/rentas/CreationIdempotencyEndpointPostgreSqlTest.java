package ar.gob.municipalidad.rentas;

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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
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
class CreationIdempotencyEndpointPostgreSqlTest {
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
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired FiscalProcessingDatePolicy fiscalDates;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void taxConfigurationCreationConcurrentReplayReturnsExactResponseAndRejectsChangedBody() throws Exception {
        String actor = "tax-config-create-" + UUID.randomUUID();
        var auth = identity(actor);
        SecurityContextHolder.getContext().setAuthentication(auth);
        TaxConcept concept = createConcept("TAX_CONFIG_IDEMP");
        var request = taxConfigurationRequest(concept.id, new BigDecimal("100.00"));
        String body = json.writeValueAsString(request);
        String key = "tax-config-" + UUID.randomUUID();

        List<MvcResult> responses = concurrentPosts("/api/v1/tax-configurations", auth, key, body);
        String first = responses.get(0).getResponse().getContentAsString();
        assertThat(responses).allSatisfy(response -> assertThat(response.getResponse().getStatus()).isEqualTo(201));
        assertThat(responses.get(1).getResponse().getContentAsString()).isEqualTo(first);
        long configurationId = json.readTree(first).path("id").asLong();
        assertThat(configurationId).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from tax_configuration where tax_concept_id=?", Long.class, concept.id)).isOne();
        assertThat(idempotencyRows(actor, "POST:/tax-configurations", key)).isOne();

        var changed = taxConfigurationRequest(concept.id, new BigDecimal("101.00"));
        mvc.perform(post("/api/v1/tax-configurations").with(authentication(auth)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(changed)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select count(*) from tax_configuration where tax_concept_id=?", Long.class, concept.id)).isOne();
    }

    @Test
    void liquidationRunCreationConcurrentReplayReturnsExactResponseAndRejectsChangedBody() throws Exception {
        String actor = "liquidation-run-create-" + UUID.randomUUID();
        var auth = identity(actor);
        SecurityContextHolder.getContext().setAuthentication(auth);
        TaxConcept concept = createConcept("RUN_CREATE_IDEMP");
        TaxpayerReference taxpayer = catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
            TaxpayerType.CITIZEN, "run-idempotency-" + UUID.randomUUID(), "12345678", null, "Synthetic run test"));
        LocalDate fiscalToday = fiscalDates.today();
        var request = liquidationRunRequest(concept.id, taxpayer.id, fiscalToday,
            fiscalToday.plusDays(30));
        String body = json.writeValueAsString(request);
        String key = "liquidation-run-" + UUID.randomUUID();

        List<MvcResult> responses = concurrentPosts("/api/v1/liquidation-runs", auth, key, body);
        String first = responses.get(0).getResponse().getContentAsString();
        assertThat(responses).allSatisfy(response -> assertThat(response.getResponse().getStatus()).isEqualTo(201));
        assertThat(responses.get(1).getResponse().getContentAsString()).isEqualTo(first);
        long runId = json.readTree(first).path("id").asLong();
        assertThat(runId).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from liquidation_run where tax_concept_id=?", Long.class, concept.id)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from liquidation_run_item where liquidation_run_id=?", Long.class, runId)).isOne();
        assertThat(idempotencyRows(actor, "POST:/liquidation-runs", key)).isOne();

        var changed = liquidationRunRequest(concept.id, taxpayer.id, fiscalToday,
            fiscalToday.plusDays(31));
        mvc.perform(post("/api/v1/liquidation-runs").with(authentication(auth)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(changed)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select count(*) from liquidation_run where tax_concept_id=?", Long.class, concept.id)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from liquidation_run_item where liquidation_run_id=?", Long.class, runId)).isOne();
    }

    @Test
    void paymentPlanConfigurationConcurrentReplayReturnsExactResponseAndRejectsChangedBody() throws Exception {
        String actor = "plan-config-create-" + UUID.randomUUID();
        var auth = identity(actor);
        String key = "plan-config-" + UUID.randomUUID();
        String body = json.writeValueAsString(planConfigurationRequest(new BigDecimal("5.00")));

        List<MvcResult> responses = concurrentPosts("/api/v1/payment-plan-configurations", auth, key, body);
        String first = responses.get(0).getResponse().getContentAsString();
        assertThat(responses).allSatisfy(response -> assertThat(response.getResponse().getStatus()).isEqualTo(201));
        assertThat(responses.get(1).getResponse().getContentAsString()).isEqualTo(first);
        long configurationId = json.readTree(first).path("id").asLong();
        assertThat(configurationId).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from payment_plan_configuration", Long.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from payment_plan_configuration where version=1", Long.class)).isOne();
        assertThat(idempotencyRows(actor, "POST:/payment-plan-configurations", key)).isOne();

        String changedBody = json.writeValueAsString(planConfigurationRequest(new BigDecimal("6.00")));
        mvc.perform(post("/api/v1/payment-plan-configurations").with(authentication(auth)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(changedBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select count(*) from payment_plan_configuration", Long.class)).isOne();
    }

    private TaxConcept createConcept(String prefix) {
        String unique = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(unique, unique, null, TaxConceptType.FEE, "M5"));
    }

    private ApiDtos.CreateTaxConfigurationRequest taxConfigurationRequest(Long conceptId, BigDecimal amount) {
        return new ApiDtos.CreateTaxConfigurationRequest(conceptId, CalculationType.FIXED, null, amount, null, null,
            true, true, LocalDate.now().minusDays(1), null);
    }

    private ApiDtos.CreateLiquidationRunRequest liquidationRunRequest(Long conceptId, Long taxpayerId,
                                                                      LocalDate fiscalToday, LocalDate dueDate) {
        return new ApiDtos.CreateLiquidationRunRequest(conceptId, YearMonth.from(fiscalToday).plusMonths(1).toString(), dueDate,
            List.of(new ApiDtos.LiquidationRunItemRequest(taxpayerId, BigDecimal.ZERO)));
    }

    private ApiDtos.CreatePaymentPlanConfigurationRequest planConfigurationRequest(BigDecimal interestRate) {
        return new ApiDtos.CreatePaymentPlanConfigurationRequest(1, 12, BigDecimal.ZERO, interestRate, 0, 2,
            true, true, 2, LocalDate.now().minusDays(1), null, true);
    }

    private long idempotencyRows(String actor, String operation, String key) {
        return jdbc.queryForObject("select count(*) from idempotency_request where actor_id=? and operation=? and idempotency_key=?",
            Long.class, actor, operation, key);
    }

    private List<MvcResult> concurrentPosts(String path, UsernamePasswordAuthenticationToken auth, String key, String body) throws Exception {
        return runTogether(
            () -> mvc.perform(post(path).with(authentication(auth)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(),
            () -> mvc.perform(post(path).with(authentication(auth)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn());
    }

    private static UsernamePasswordAuthenticationToken identity(String actor) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(actor, null), null,
            List.of(new SimpleGrantedAuthority("ROLE_RENTAS")));
    }

    private static List<MvcResult> runTogether(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = List.of(
                pool.submit(task(first, ready, start)), pool.submit(task(second, ready, start)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<MvcResult> task(Callable<MvcResult> action, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try { return action.call(); }
            finally { SecurityContextHolder.clearContext(); }
        };
    }
}
