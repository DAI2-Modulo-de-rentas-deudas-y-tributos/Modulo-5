package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OutboxRetryHttpTests {
    @Autowired MockMvc mvc;
    @Autowired OutboxRepository outbox;
    @Autowired AuditRepository audits;
    @Autowired DemoUserRepository users;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService auth;

    private DemoAuthSessions sessions;

    @BeforeEach void createSessions() {
        sessions = new DemoAuthSessions(users, taxpayers, encoder, auth);
    }

    @Test void supervisorQueuesFailedEventWithoutChangingItsIdentityOrPayloadAndRejectsASecondQueue() throws Exception {
        UUID id = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + id + "\",\"eventType\":\"debtSettled\"}";
        OutboxEvent event = event(id, OutboxStatus.DEAD_LETTER, 5, payload, "CORE");
        outbox.saveAndFlush(event);
        String supervisor = sessions.token(DemoRole.SUPERVISOR);

        mvc.perform(post("/api/v1/integrations/outbox/{id}/retry", id)
                .header("X-Demo-Session", supervisor))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.retryCount").value(0))
            .andExpect(jsonPath("$.payload").doesNotExist());

        mvc.perform(post("/api/v1/integrations/outbox/{id}/retry", id)
                .header("X-Demo-Session", supervisor))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("OUTBOX_EVENT_NOT_RETRYABLE"));

        OutboxEvent saved = outbox.findById(id).orElseThrow();
        assertThat(saved.status).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.retryCount).isZero();
        assertThat(saved.payload).isEqualTo(payload);
        assertThat(audits.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc("OutboxEvent", id.toString()))
            .filteredOn(entry -> entry.action.equals("OUTBOX_MANUAL_RETRY_QUEUED"))
            .singleElement()
            .satisfies(entry -> assertThat(entry.previousData).contains("\"retryCount\":5"));
    }

    @Test void failedEventCanBeManuallyRetried() throws Exception {
        UUID id = UUID.randomUUID();
        outbox.saveAndFlush(event(id, OutboxStatus.FAILED, 2, "{}", "CORE"));

        mvc.perform(post("/api/v1/integrations/outbox/{id}/retry", id)
                .header("X-Demo-Session", sessions.token(DemoRole.SUPERVISOR)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.retryCount").value(0));
    }

    @Test void nonSupervisorCannotQueueFailedEvent() throws Exception {
        UUID id = UUID.randomUUID();
        outbox.saveAndFlush(event(id, OutboxStatus.FAILED, 1, "{}", "CORE"));

        mvc.perform(post("/api/v1/integrations/outbox/{id}/retry", id)
                .header("X-Demo-Session", sessions.token(DemoRole.RENTAS)))
            .andExpect(status().isForbidden());

        assertThat(outbox.findById(id).orElseThrow().status).isEqualTo(OutboxStatus.FAILED);
        assertThat(audits.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc("OutboxEvent", id.toString()))
            .noneMatch(entry -> entry.action.equals("OUTBOX_MANUAL_RETRY_QUEUED"));
    }

    @Test void onlyFailedOrDeadLetterEventsCanBeQueued() throws Exception {
        for (OutboxStatus state : new OutboxStatus[]{OutboxStatus.PENDING, OutboxStatus.LOCAL_ONLY,
                OutboxStatus.PUBLISHED, OutboxStatus.BROKER_CONFIRMED, OutboxStatus.CORE_ACCEPTED}) {
            UUID id = UUID.randomUUID();
            outbox.saveAndFlush(event(id, state, 1, "{}", "CORE"));
            mvc.perform(post("/api/v1/integrations/outbox/{id}/retry", id)
                    .header("X-Demo-Session", sessions.token(DemoRole.SUPERVISOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTBOX_EVENT_NOT_RETRYABLE"));
            assertThat(outbox.findById(id).orElseThrow().status).isEqualTo(state);
        }
    }

    @Test void missingEventReturnsNotFound() throws Exception {
        mvc.perform(post("/api/v1/integrations/outbox/{id}/retry", UUID.randomUUID())
                .header("X-Demo-Session", sessions.token(DemoRole.SUPERVISOR)))
            .andExpect(status().isNotFound());
    }

    @Test void staleOutboxTagDoesNotQueueRetryAndCurrentTagDoes() throws Exception {
        UUID id=UUID.randomUUID();outbox.saveAndFlush(event(id,OutboxStatus.FAILED,3,"{}","CORE"));
        String supervisor=sessions.token(DemoRole.SUPERVISOR);String path="/api/v1/integrations/outbox/"+id;
        String tag=mvc.perform(get(path).header("X-Demo-Session",supervisor)).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        mvc.perform(post(path+"/retry").header("X-Demo-Session",supervisor).header("If-Match","\"stale\""))
            .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        assertThat(outbox.findById(id).orElseThrow().status).isEqualTo(OutboxStatus.FAILED);
        var queued=mvc.perform(post(path+"/retry").header("X-Demo-Session",supervisor).header("If-Match",tag))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING")).andReturn().getResponse();
        assertThat(queued.getHeader("ETag")).isNotBlank().isNotEqualTo(tag);
    }

    private OutboxEvent event(UUID id, OutboxStatus status, int retryCount, String payload, String target) {
        OutboxEvent event = new OutboxEvent();
        event.id = id;
        event.eventType = "debtSettled";
        event.targetModule = target;
        event.aggregateType = "Debt";
        event.aggregateId = id.toString();
        event.payload = payload;
        event.status = status;
        event.retryCount = retryCount;
        event.createdAt = OffsetDateTime.now();
        event.errorMessage = status == OutboxStatus.FAILED || status == OutboxStatus.DEAD_LETTER ? "CORE_BROKER_PUBLISH_FAILED" : null;
        return event;
    }
}
