package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker = true)
class OutboxRetryPostgreSqlTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine");

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/migration-postgresql,classpath:db/migration-demo");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRepository outbox;
    @Autowired DemoUserRepository users;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService auth;

    @Test void concurrentReplayQueuesOneOutboxEventAndReturnsTheSameResult() throws Exception {
        UUID id = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + id + "\",\"eventType\":\"debtSettled\"}";
        OutboxEvent event = new OutboxEvent();
        event.id = id;
        event.eventType = "debtSettled";
        event.targetModule = "CORE";
        event.aggregateType = "Debt";
        event.aggregateId = id.toString();
        event.payload = payload;
        event.status = OutboxStatus.DEAD_LETTER;
        event.retryCount = 5;
        event.createdAt = OffsetDateTime.now();
        event.errorMessage = "CORE_BROKER_PUBLISH_FAILED";
        outbox.saveAndFlush(event);

        String supervisor = new DemoAuthSessions(users, taxpayers, encoder, auth).token(DemoRole.SUPERVISOR);
        String key = "retry-" + id;
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> retry(supervisor, id, key, ready, start));
            Future<Integer> second = executor.submit(() -> retry(supervisor, id, key, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(202);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(202);
        } finally {
            executor.shutdownNow();
        }

        OutboxEvent queued = outbox.findById(id).orElseThrow();
        assertThat(queued.status).isEqualTo(OutboxStatus.PENDING);
        assertThat(queued.retryCount).isZero();
        assertThat(queued.payload).isEqualTo(payload);
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where entity_type='OutboxEvent' and entity_id=? and action='OUTBOX_MANUAL_RETRY_QUEUED'", Long.class, id.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where operation=? and idempotency_key=?", Long.class,
            "POST:/integrations/outbox/{id}/retry:" + id, key)).isEqualTo(1);
    }

    private int retry(String supervisor, UUID id, String key, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test start timed out");
        return mvc.perform(post("/api/v1/integrations/outbox/{id}/retry", id)
                .header("X-Demo-Session", supervisor).header("Idempotency-Key", key))
            .andReturn().getResponse().getStatus();
    }
}
