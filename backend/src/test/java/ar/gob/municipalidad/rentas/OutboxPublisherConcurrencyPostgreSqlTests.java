package ar.gob.municipalidad.rentas;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"rentas.outbox-delay-ms=3600000"})
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker = true)
class OutboxPublisherConcurrencyPostgreSqlTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/migration-postgresql,classpath:db/migration-demo");
    }

    @Autowired OutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager entityManager;

    @Test
    void concurrentPublishersReleaseBatchLocksAndRevalidateEachCandidateBeforePublishing() throws Exception {
        OutboxEvent first = event("blocking-first", OffsetDateTime.now().minusSeconds(2));
        OutboxEvent second = event("independent-second", OffsetDateTime.now().minusSeconds(1));
        outbox.saveAndFlush(first);
        outbox.saveAndFlush(second);

        CountDownLatch firstPublishStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstPublish = new CountDownLatch(1);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        EventPublisher sender = (eventType, target, payload) -> {
            if ("blocking-first".equals(eventType)) {
                firstCalls.incrementAndGet();
                firstPublishStarted.countDown();
                try {
                    if (!releaseFirstPublish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_FIRST_PUBLISH_TIMEOUT");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("TEST_FIRST_PUBLISH_INTERRUPTED", ex);
                }
            } else if ("independent-second".equals(eventType)) {
                secondCalls.incrementAndGet();
            }
            return OutboxStatus.BROKER_CONFIRMED;
        };
        OutboxPublisher firstPublisher = publisher(sender);
        OutboxPublisher secondPublisher = publisher(sender);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstRun = executor.submit(firstPublisher::publishPending);
            assertThat(firstPublishStarted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> secondRun = executor.submit(secondPublisher::publishPending);
            secondRun.get(10, TimeUnit.SECONDS);

            assertThat(firstRun.isDone()).isFalse();
            assertThat(firstCalls.get()).isEqualTo(1);
            assertThat(secondCalls.get()).isEqualTo(1);
        } finally {
            releaseFirstPublish.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(outbox.findById(first.id).orElseThrow().status).isEqualTo(OutboxStatus.BROKER_CONFIRMED);
        assertThat(outbox.findById(second.id).orElseThrow().status).isEqualTo(OutboxStatus.BROKER_CONFIRMED);
        assertThat(firstCalls.get()).isEqualTo(1);
        assertThat(secondCalls.get()).isEqualTo(1);
    }

    private OutboxPublisher publisher(EventPublisher sender) {
        return new OutboxPublisher(outbox, sender, "rabbitmq", "", "", transactionManager, entityManager);
    }

    private OutboxEvent event(String type, OffsetDateTime createdAt) {
        OutboxEvent event = new OutboxEvent();
        event.id = UUID.randomUUID();
        event.eventType = type;
        event.targetModule = "M1";
        event.aggregateType = "Test";
        event.aggregateId = event.id.toString();
        event.payload = "{}";
        event.status = OutboxStatus.PENDING;
        event.retryCount = 0;
        event.createdAt = createdAt;
        return event;
    }
}
