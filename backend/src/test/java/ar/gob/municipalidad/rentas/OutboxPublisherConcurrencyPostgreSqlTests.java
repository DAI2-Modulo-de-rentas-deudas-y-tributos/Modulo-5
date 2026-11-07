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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

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
    @Autowired JdbcTemplate jdbc;
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

    @Test
    void coreHttpPublishesOnlyAllowlistedPersistedEventIdThroughPerEventTransaction() {
        UUID id = UUID.randomUUID();
        OutboxEvent event = event("probe", OffsetDateTime.now());
        event.id = id;
        event.targetModule = "CORE";
        event.payload = envelope(id);
        outbox.saveAndFlush(event);
        AtomicInteger calls = new AtomicInteger();

        OutboxPublisher core = new OutboxPublisher(outbox, (type, target, payload) -> {
            calls.incrementAndGet();
            assertThat(payload).isEqualTo(event.payload);
            return OutboxStatus.CORE_ACCEPTED;
        }, "core-http", "probe", id.toString(), transactionManager, entityManager);

        core.publishPending();

        OutboxEvent published = outbox.findById(id).orElseThrow();
        assertThat(calls).hasValue(1);
        assertThat(published.status).isEqualTo(OutboxStatus.CORE_ACCEPTED);
        assertThat(published.publishedAt).isNotNull();
        assertThat(published.payload).contains("\"eventId\":\"" + id + "\"");
    }

    @Test
    void coreHttpSkipsAllowlistedRowWhenPayloadEventIdDoesNotMatchRowId() {
        UUID rowId = UUID.randomUUID();
        UUID payloadId = UUID.randomUUID();
        OutboxEvent event = event("probe", OffsetDateTime.now());
        event.id = rowId;
        event.targetModule = "CORE";
        event.payload = envelope(payloadId);
        outbox.saveAndFlush(event);
        AtomicInteger calls = new AtomicInteger();

        OutboxPublisher core = new OutboxPublisher(outbox, (type, target, payload) -> {
            calls.incrementAndGet();
            return OutboxStatus.CORE_ACCEPTED;
        }, "core-http", "probe", rowId.toString(), transactionManager, entityManager);

        core.publishPending();

        OutboxEvent skipped = outbox.findById(rowId).orElseThrow();
        assertThat(calls).hasValue(0);
        assertThat(skipped.status).isEqualTo(OutboxStatus.PENDING);
        assertThat(skipped.publishedAt).isNull();
        assertThat(skipped.payload).contains("\"eventId\":\"" + payloadId + "\"");
    }

    @Test
    void failedCandidatesAreRevalidatedAgainstPersistedBackoffForEveryRetryBand() {
        for (int retryCount = 1; retryCount <= 4; retryCount++) {
            long backoff = retryCount == 1 ? 5 : retryCount == 2 ? 10 : retryCount == 3 ? 20 : 40;
            assertFailedCandidate(retryCount, OffsetDateTime.now().minusSeconds(backoff + 2), true);
            assertFailedCandidate(retryCount, null, true);
            assertFailedCandidate(retryCount, OffsetDateTime.now().minusSeconds(backoff - 1), false);
        }
    }

    private void assertFailedCandidate(int retryCount, OffsetDateTime lastAttemptAt, boolean publishExpected) {
        OutboxEvent candidate = event("retry-probe-" + UUID.randomUUID(), OffsetDateTime.now());
        outbox.saveAndFlush(candidate);
        OutboxRepository scannedCandidates = mock(OutboxRepository.class);
        TransactionTemplate separateUpdate = new TransactionTemplate(transactionManager);
        separateUpdate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            separateUpdate.executeWithoutResult(status -> jdbc.update(
                "update outbox_event set status='FAILED', retry_count=?, last_attempt_at=? where id=?",
                retryCount, lastAttemptAt, candidate.id));
            return List.of(candidate);
        }).when(scannedCandidates).findPublishable(any(Pageable.class));

        AtomicInteger calls = new AtomicInteger();
        OutboxPublisher publisher = new OutboxPublisher(scannedCandidates, (type, target, payload) -> {
            calls.incrementAndGet();
            return OutboxStatus.BROKER_CONFIRMED;
        }, "rabbitmq", "", "", transactionManager, entityManager);

        publisher.publishPending();

        OutboxEvent reloaded = outbox.findById(candidate.id).orElseThrow();
        assertThat(calls.get()).isEqualTo(publishExpected ? 1 : 0);
        assertThat(reloaded.status).isEqualTo(publishExpected ? OutboxStatus.BROKER_CONFIRMED : OutboxStatus.FAILED);
        if (publishExpected) assertThat(reloaded.publishedAt).isNotNull();
        else assertThat(reloaded.publishedAt).isNull();
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

    private String envelope(UUID eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"probe\",\"eventVersion\":\"1.0\","
            + "\"occurredAt\":\"2026-11-07T12:30:00-03:00\",\"sourceModule\":\"rentas\","
            + "\"correlationId\":\"" + UUID.randomUUID() + "\",\"causationId\":null,\"data\":{}}";
    }
}
