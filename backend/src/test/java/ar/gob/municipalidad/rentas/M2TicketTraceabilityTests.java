package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test") @SpringBootTest @Transactional
class M2TicketTraceabilityTests {
    private static final UUID TICKET_ID=UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID THREAD_ID=UUID.fromString("294e7015-0e6a-4bd1-8240-ce3148fd937d");
    @Autowired ConfirmedM2Consumer consumer;@Autowired TicketService tickets;
    @Autowired TicketCaseRepository cases;@Autowired TicketCaseUpdateRepository updates;
    @Autowired OutboxRepository outbox;@Autowired ObjectMapper json;@Autowired OutboxPublisher publisher;

    @BeforeEach void authenticate(){SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("empleado.m5",null,
            UUID.fromString("3b9f1c52-6a7e-4d08-9c41-5e2a8d7f0b13")),null,
            List.of(new SimpleGrantedAuthority("ROLE_RENTAS"))));}
    @AfterEach void clearAuthentication(){SecurityContextHolder.clearContext();}

    @Test void routedDuplicateAndNewerRepeatKeepOneCaseAndOneStart() throws Exception {
        var routed=event(0,"ROUTED","ROUTED",route(false));TicketCase ticket=consumer.consume(routed);
        assertThat(consumer.consume(routed).id).isEqualTo(ticket.id);
        assertThat(cases.count()).isEqualTo(1);
        var repeat=event(1,"ROUTED","ROUTED",route(false));
        assertThat(consumer.consume(repeat).id).isEqualTo(ticket.id);
        assertThat(ticket.lastSourceEventId).isEqualTo(repeat.eventId().toString());
        tickets.assign(ticket.id);
        assertThatThrownBy(()->tickets.assign(ticket.id)).isInstanceOf(BusinessException.class);
        assertThat(events("STARTED")).hasSize(1);
        assertThat(payload(events("STARTED").get(0)).path("causationId").asText()).isEqualTo(repeat.eventId().toString());
    }

    @Test void priorityAndEscalationKeepTheApplicableRoutedCause() throws Exception {
        var routed=event(0,"ROUTED","ROUTED",route(false));TicketCase ticket=consumer.consume(routed);
        consumer.consume(event(1,"PRIORITY_CHANGED","ROUTED",null));
        consumer.consume(event(2,"ESCALATION_CHANGED","ROUTED",json.readTree("{\"escalation\":{\"active\":true}}")));
        assertThat(ticket.status).isEqualTo(TicketCaseStatus.OPEN);
        assertThat(ticket.lastSourceEventId).isEqualTo(routed.eventId().toString());
        tickets.assign(ticket.id);
        JsonNode started=payload(events("STARTED").get(0));
        assertThat(started.path("correlationId").asText()).isEqualTo(THREAD_ID.toString());
        assertThat(started.path("causationId").asText()).isEqualTo(routed.eventId().toString());
        assertThat(started.path("data").path("ticketId").asText()).isEqualTo(TICKET_ID.toString());
    }

    @Test void laterRoutedWithoutReassignmentCannotRestartActiveCase() throws Exception {
        TicketCase ticket=consumer.consume(event(0,"ROUTED","ROUTED",route(false)));tickets.assign(ticket.id);
        long before=outbox.count();
        assertThatThrownBy(()->consumer.consume(event(1,"ROUTED","ROUTED",route(false))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("reasignación");
        assertThat(ticket.status).isEqualTo(TicketCaseStatus.IN_PROGRESS);
        assertThat(outbox.count()).isEqualTo(before);
    }

    @Test void genuineReassignmentReplacesRoutedCauseAndClearsAssignment() throws Exception {
        TicketCase ticket=consumer.consume(event(0,"ROUTED","ROUTED",route(false)));tickets.assign(ticket.id);
        var rerouted=event(1,"ROUTED","ROUTED",route(true));
        assertThat(consumer.consume(rerouted).id).isEqualTo(ticket.id);
        assertThat(ticket.status).isEqualTo(TicketCaseStatus.OPEN);
        assertThat(ticket.assignedTo).isNull();
        tickets.assign(ticket.id);
        assertThat(events("STARTED")).hasSize(2);
        assertThat(payload(events("STARTED").get(1)).path("causationId").asText()).isEqualTo(rerouted.eventId().toString());
    }

    @Test void missingOrContradictoryCorrelationNeverPreparesAnEvent() throws Exception {
        var first=event(0,"ROUTED","ROUTED",route(false));
        var missing=new ConfirmedInboundEvents.M2TicketUpdatedEvent(first.eventId(),first.eventType(),first.eventVersion(),
            first.occurredAt(),first.sourceModule(),null,null,first.data());
        TicketCase ticket=consumer.consume(missing);
        assertThatThrownBy(()->tickets.assign(ticket.id)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("correlationId original");
        assertThat(ticket.status).isEqualTo(TicketCaseStatus.OPEN);assertThat(outbox.count()).isZero();
        var later=event(1,"PRIORITY_CHANGED","ROUTED",null);
        assertThatThrownBy(()->consumer.consume(later)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("hilo ROUTED original");
        assertThat(ticket.correlationId).isNull();
    }

    @Test void conflictingThreadCannotOverwriteOriginalCorrelation() throws Exception {
        TicketCase ticket=consumer.consume(event(0,"ROUTED","ROUTED",route(false)));
        var next=event(1,"PRIORITY_CHANGED","ROUTED",null);
        var conflict=new ConfirmedInboundEvents.M2TicketUpdatedEvent(next.eventId(),next.eventType(),next.eventVersion(),
            next.occurredAt(),next.sourceModule(),UUID.randomUUID(),null,next.data());
        assertThatThrownBy(()->consumer.consume(conflict)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("hilo ROUTED original");
        assertThat(ticket.correlationId).isEqualTo(THREAD_ID.toString());
    }

    @Test void progressUsesInternalMessageOnlyWhileInProgress() throws Exception {
        TicketCase ticket=consumer.consume(event(0,"ROUTED","ROUTED",route(false)));
        assertThatThrownBy(()->tickets.note(ticket.id,"Avance prematuro")).isInstanceOf(BusinessException.class);
        assertThat(outbox.count()).isZero();
        tickets.assign(ticket.id);tickets.note(ticket.id,"Se verifico el comprobante");
        JsonNode progress=payload(events("PROGRESS").get(0));
        assertThat(progress.path("data").path("internalMessage").asText()).isEqualTo("Se verifico el comprobante");
        assertThat(progress.path("data").path("publicMessage").isNull()).isTrue();
        assertThat(progress.path("causationId").isNull()).isTrue();
        assertThat(ticket.status).isEqualTo(TicketCaseStatus.IN_PROGRESS);
        tickets.complete(ticket.id,"Completado");
        assertThatThrownBy(()->tickets.note(ticket.id,"Avance tardio")).isInstanceOf(BusinessException.class);
        assertThat(events("PROGRESS")).hasSize(1);
    }

    @Test void returnedKeepsAdministrativeExplanationPrivateAndRequiresNewRoute() throws Exception {
        TicketCase ticket=consumer.consume(event(0,"ROUTED","ROUTED",route(false)));
        tickets.returnToM2(ticket.id,"REQUEST_TYPE_MISMATCH","Corresponde a otra area");
        JsonNode returned=payload(events("RETURNED").get(0));
        assertThat(returned.path("data").path("internalMessage").asText()).isEqualTo("Corresponde a otra area");
        assertThat(returned.path("data").path("publicMessage").isNull()).isTrue();
        assertThat(returned.path("data").path("details").path("returnInfo").path("reasonCode").asText())
            .isEqualTo("REQUEST_TYPE_MISMATCH");
        assertThat(ticket.status).isEqualTo(TicketCaseStatus.IN_REVIEW);
        assertThatThrownBy(()->tickets.assign(ticket.id)).isInstanceOf(BusinessException.class);
        consumer.consume(event(1,"ROUTED","ROUTED",route(true)));
        tickets.assign(ticket.id);
        assertThat(events("STARTED")).hasSize(1);
    }

    @Test void informationResponseAndReclassificationHaveDifferentEffects() throws Exception {
        TicketCase ticket=consumer.consume(event(0,"ROUTED","ROUTED",route(false)));
        tickets.requestInformation(ticket.id,"Adjunte el comprobante");
        assertThat(ticket.status).isEqualTo(TicketCaseStatus.WAITING_FOR_INFORMATION);
        consumer.consume(event(1,"INFORMATION_PROVIDED","IN_PROGRESS",
            json.readTree("{\"informationResponse\":{\"message\":\"Comprobante adjunto\"}}")));
        assertThat(ticket.status).isEqualTo(TicketCaseStatus.IN_PROGRESS);
        assertThat(updates.findByTicketCaseIdOrderByCreatedAt(ticket.id)).anyMatch(u->"M2".equals(u.createdBy));
        long before=updates.count();
        consumer.consume(event(2,"INFORMATION_PROVIDED","IN_REVIEW",
            json.readTree("{\"informationResponse\":{\"purpose\":\"RECLASSIFICATION\",\"message\":\"Cambio administrativo\"}}")));
        assertThat(updates.count()).isEqualTo(before);
        assertThat(ticket.status).isEqualTo(TicketCaseStatus.IN_PROGRESS);
    }

    @Test void m2CancellationForExpiredInformationDiffersFromM5Rejection() throws Exception {
        TicketCase ticket=consumer.consume(event(0,"ROUTED","ROUTED",route(false)));
        tickets.requestInformation(ticket.id,"Adjunte informacion");long before=outbox.count();
        consumer.consume(event(1,"CANCELLED","CANCELLED",
            json.readTree("{\"cancellation\":{\"reasonCode\":\"INFORMATION_TIMEOUT\"}}")));
        assertThat(ticket.status).isEqualTo(TicketCaseStatus.CANCELLED);
        assertThat(ticket.status).isNotEqualTo(TicketCaseStatus.REJECTED);
        assertThat(outbox.count()).isEqualTo(before);
        assertThatThrownBy(()->tickets.reject(ticket.id,"Rechazo local tardio")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->consumer.consume(event(2,"RESOLVED","RESOLVED",null)))
            .isInstanceOf(BusinessException.class);
        assertThat(ticket.status).isEqualTo(TicketCaseStatus.CANCELLED);
        assertThat(events("REJECTED")).isEmpty();
    }

    @Test void reopenedReturnsOnlyAResolvedTicketToInProgress() throws Exception {
        TicketCase ticket=consumer.consume(event(0,"ROUTED","ROUTED",route(false)));
        tickets.complete(ticket.id,"Resuelto");
        consumer.consume(event(1,"REOPENED","IN_PROGRESS",
            json.readTree("{\"reopen\":{\"reason\":\"Disconformidad\"}}")));
        assertThat(ticket.status).isEqualTo(TicketCaseStatus.IN_PROGRESS);
        assertThat(ticket.completedAt).isNull();
        assertThat(events("STARTED")).isEmpty();
        tickets.note(ticket.id,"Nueva revision");
        assertThat(events("PROGRESS")).hasSize(1);
    }

    @Test void outboxRetryKeepsTheSameEventIdAndNullDataFields() throws Exception {
        TicketCase ticket=consumer.consume(event(0,"ROUTED","ROUTED",route(false)));tickets.assign(ticket.id);
        OutboxEvent started=events("STARTED").get(0);UUID id=started.id;String body=started.payload;
        JsonNode payload=payload(started);
        assertThat(payload.path("data").path("internalMessage").isNull()).isTrue();
        assertThat(payload.path("data").path("progress").isNull()).isTrue();
        assertThat(payload.path("causationId").asText()).isEqualTo(ticket.lastSourceEventId);
        started.status=OutboxStatus.FAILED;started.lastAttemptAt=null;outbox.saveAndFlush(started);
        publisher.publishPending();
        OutboxEvent retried=outbox.findById(id).orElseThrow();
        assertThat(retried.status).isEqualTo(OutboxStatus.LOCAL_ONLY);
        assertThat(retried.payload).isEqualTo(body);
        assertThat(retried.id).isEqualTo(id);
        publisher.publishPending();assertThat(outbox.count()).isEqualTo(1);
    }

    private ConfirmedInboundEvents.M2TicketUpdatedEvent event(int minute,String type,String status,JsonNode details){
        OffsetDateTime at=OffsetDateTime.parse("2026-10-29T10:00:00-03:00").plusMinutes(minute);
        var data=new ConfirmedInboundEvents.M2TicketUpdatedData(TICKET_ID,"TK-2026-000123",null,true,
            "M5",type,status,"HIGH",null,null,details,List.of(),at);
        return new ConfirmedInboundEvents.M2TicketUpdatedEvent(UUID.randomUUID(),"ticketUpdated","1.0",at,
            "atencion-ciudadana",THREAD_ID,null,data);
    }
    private JsonNode route(boolean reassignment){var root=json.createObjectNode();var routing=root.putObject("routing");
        routing.put("requestType","Consulta por deuda");routing.put("category","Tributos");routing.put("subcategory","Deudas");
        routing.put("ticketType","INQUIRY");routing.put("summary","Saldo");routing.put("description","Consulta de saldo");
        routing.put("reassignment",reassignment);return root;}
    private List<OutboxEvent> events(String type){return outbox.findAll().stream().filter(e->"updateTicketStatus".equals(e.eventType))
        .filter(e->type.equals(payload(e).path("data").path("updateType").asText())).toList();}
    private JsonNode payload(OutboxEvent e){try{return json.readTree(e.payload);}catch(Exception ex){throw new AssertionError(ex);}}
}
