package ar.gob.municipalidad.rentas;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OperationalLogPrivacyTests {
    private static final String DNI = "40111222";
    private static final String CUIT = "20-40111222-3";
    private static final String PAYMENT_DATA = "4111111111111111";
    private static final String PAYLOAD = "{\"dni\":\"" + DNI + "\",\"cuit\":\"" + CUIT
        + "\",\"amount\":1250.50,\"cardNumber\":\"" + PAYMENT_DATA + "\"}";

    @Test
    void eventPublishersLogTechnicalMetadataWithoutPayload() {
        Logger localLogger = logger(LocalLogEventPublisher.class);
        Logger outboxLogger = logger(OutboxPublisher.class);
        ListAppender<ILoggingEvent> localLogs = capture(localLogger);
        ListAppender<ILoggingEvent> outboxLogs = capture(outboxLogger);
        try {
            new LocalLogEventPublisher().publish("paymentRegistered", "M4", PAYLOAD);

            OutboxRepository repository = mock(OutboxRepository.class);
            EventPublisher publisher = mock(EventPublisher.class);
            OutboxEvent event = new OutboxEvent();
            event.id = UUID.randomUUID();
            event.eventType = "paymentRegistered";
            event.targetModule = "PLATFORM";
            event.payload = PAYLOAD;
            event.status = OutboxStatus.PENDING;
            event.createdAt = OffsetDateTime.now();
            when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));

            new OutboxPublisher(repository, publisher).publishPending();

            assertSafeLogs(localLogs, "eventType=paymentRegistered", "targetModule=M4");
            assertSafeLogs(outboxLogs, "eventType=paymentRegistered", "targetModule=PLATFORM");
            assertThat(event.status).isEqualTo(OutboxStatus.LOCAL_ONLY);
            verifyNoInteractions(publisher);
            assertThat(event.payload).isEqualTo(PAYLOAD);
        } finally {
            localLogger.detachAppender(localLogs);
            outboxLogger.detachAppender(outboxLogs);
        }
    }

    @Test
    void failedOutboxPublishLogsSafeIdentifiersAndStackFrames() {
        Logger outboxLogger = logger(OutboxPublisher.class);
        ListAppender<ILoggingEvent> logs = capture(outboxLogger);
        try {
            UUID eventId = UUID.randomUUID();
            UUID correlationId = UUID.randomUUID();
            String payload = "{\"eventId\":\"" + eventId + "\",\"correlationId\":\"" + correlationId
                + "\",\"data\":{\"dni\":\"" + DNI + "\",\"cuit\":\"" + CUIT
                + "\",\"token\":\"secret-token\"}}";
            OutboxEvent event = new OutboxEvent();
            event.id = UUID.randomUUID();
            event.eventType = "testEvent";
            event.targetModule = "CORE";
            event.payload = payload;
            event.status = OutboxStatus.PENDING;
            event.createdAt = OffsetDateTime.now();
            OutboxRepository repository = mock(OutboxRepository.class);
            when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));

            new OutboxPublisher(repository, (type, target, body) -> {
                throw new IllegalStateException("DNI=" + DNI + " CUIT=" + CUIT + " secret-token " + payload);
            }).publishPending();

            assertSafeLogs(logs, "outboxId=" + event.id, "eventId=" + eventId,
                "correlationId=" + correlationId, "exceptionType=IllegalStateException");
            assertHasSafeStackTrace(logs);
            assertThat(event.status).isEqualTo(OutboxStatus.FAILED);
        } finally {
            outboxLogger.detachAppender(logs);
        }
    }

    @Test
    void unexpectedErrorLogsCorrelationAndTypeButNotExceptionDetails() {
        Logger errorLogger = logger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = capture(errorLogger);
        try {
            MDC.put("traceId", "correlation-demo-123");
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRequestURI()).thenReturn("/api/v1/payments");

            new GlobalExceptionHandler().unexpected(
                new IllegalStateException("DNI=" + DNI + " CUIT=" + CUIT + " card=" + PAYMENT_DATA), request);

            assertSafeLogs(logs, "traceId=correlation-demo-123", "exceptionType=IllegalStateException");
            assertHasSafeStackTrace(logs);
        } finally {
            MDC.remove("traceId");
            errorLogger.detachAppender(logs);
        }
    }

    private static Logger logger(Class<?> type) {
        return (Logger) LoggerFactory.getLogger(type);
    }

    private static ListAppender<ILoggingEvent> capture(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void assertSafeLogs(ListAppender<ILoggingEvent> appender, String... expectedMetadata) {
        String output = appender.list.stream().map(event -> event.getFormattedMessage() + " "
                + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage()))
            .reduce("", (left, right) -> left + "\n" + right);
        assertThat(output).contains(expectedMetadata)
            .doesNotContain(DNI, CUIT, PAYMENT_DATA, "secret-token", "amount=1250.50", PAYLOAD);
    }

    private static void assertHasSafeStackTrace(ListAppender<ILoggingEvent> appender) {
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
        });
    }
}
