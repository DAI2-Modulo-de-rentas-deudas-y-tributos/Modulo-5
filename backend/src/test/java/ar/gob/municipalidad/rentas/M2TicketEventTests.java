package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class M2TicketEventTests {
    @Autowired MockMvc mvc;
    @Autowired TicketCaseRepository tickets;
    @Autowired TicketCaseUpdateRepository updates;
    @Autowired ProcessedEventRepository processed;
    @Autowired IntegrationEventLogRepository eventLog;
    @Autowired IntegrationReprocessService reprocess;
    @Autowired OutboxRepository outbox;

    @Test void simulaCreacionActualizacionEIdempotenciaYPermiteTomarElTicket() throws Exception {
        UUID createdId=UUID.randomUUID();
        String created="""
            {"eventId":"%s","eventType":"ticketCreated","occurredAt":"2026-09-03T10:00:00-03:00","sourceModule":"M2","data":{"ticketId":1001,"citizenId":123,"category":"RENTAS","description":"El pago no aparece imputado","priority":"HIGH"}}
            """.formatted(createdId);

        mvc.perform(post("/api/v1/dev/integrations/m2/events").headers(supervisor()).contentType(MediaType.APPLICATION_JSON).content(created))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.externalTicketId").value("1001")).andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(post("/api/v1/dev/integrations/m2/events").headers(supervisor()).contentType(MediaType.APPLICATION_JSON).content(created))
            .andExpect(status().isAccepted());

        TicketCase ticket=tickets.findByExternalTicketId("1001").orElseThrow();
        assertThat(processed.existsByExternalEventId(createdId.toString())).isTrue();
        assertThat(eventLog.findFirstByExternalEventIdOrderByIdDesc(createdId.toString()).orElseThrow().status).isEqualTo(IntegrationEventStatus.PROCESSED);

        UUID updatedId=UUID.randomUUID();
        String updated="""
            {"eventId":"%s","eventType":"ticketUpdated","occurredAt":"2026-09-03T10:05:00-03:00","sourceModule":"M2","data":{"ticketId":1001,"additionalInformation":"El ciudadano adjuntó información"}}
            """.formatted(updatedId);
        mvc.perform(post("/api/v1/dev/integrations/m2/events").headers(supervisor()).contentType(MediaType.APPLICATION_JSON).content(updated))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(post("/api/v1/dev/integrations/m2/events").headers(supervisor()).contentType(MediaType.APPLICATION_JSON).content(updated))
            .andExpect(status().isAccepted());
        assertThat(updates.findByTicketCaseIdOrderByCreatedAt(ticket.id)).singleElement().extracting(x->x.message).isEqualTo("El ciudadano adjuntó información");

        mvc.perform(post("/api/v1/tickets/{id}/assign",ticket.id).header("X-Dev-User","qa-rentas").header("X-Dev-Roles","RENTAS"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        assertThat(outbox.findAll()).anyMatch(x->x.eventType.equals("updateTicketStatus")&&x.targetModule.equals("M2"));
    }

    @Test void reintentaUnEventoFallidoHastaEnviarloALaColaDeErrores() {
        UUID eventId=UUID.randomUUID();OffsetDateTime now=OffsetDateTime.now();
        IntegrationEventLog failed=new IntegrationEventLog();failed.eventId=eventId;failed.externalEventId=eventId.toString();failed.eventType="ticketUpdated";failed.sourceModule="M2";failed.direction=EventDirection.INBOUND;failed.status=IntegrationEventStatus.FAILED;failed.payload="""
            {"eventId":"%s","eventType":"ticketUpdated","occurredAt":"%s","sourceModule":"M2","data":{"ticketId":9999}}
            """.formatted(eventId,now);failed.retryCount=0;failed.errorMessage="Fallo simulado del adaptador";failed.occurredAt=now;failed.receivedAt=now;eventLog.save(failed);

        for(int retry=1;retry<=5;retry++){
            IntegrationEventLog result=reprocess.reprocess(eventId.toString(),"Reintento QA");
            assertThat(result.retryCount).isEqualTo(retry);
            assertThat(result.status).isEqualTo(retry<5?IntegrationEventStatus.FAILED:IntegrationEventStatus.DEAD_LETTER);
        }
        assertThat(eventLog.findFirstByExternalEventIdOrderByIdDesc(eventId.toString()).orElseThrow().errorMessage).contains("ticketUpdated requiere additionalInformation");
    }

    private org.springframework.http.HttpHeaders supervisor(){
        var headers=new org.springframework.http.HttpHeaders();headers.set("X-Dev-User","qa-supervisor");headers.set("X-Dev-Roles","SUPERVISOR");return headers;
    }
}

@ActiveProfiles("test")
@SpringBootTest(properties="rentas.security.dev-mode=false")
class M2TicketEventSimulationDisabledTests {
    @Autowired ApplicationContext context;

    @Test
    void noExponeElSimuladorFueraDelModoDesarrollo() {
        assertThat(context.getBeansOfType(DevM2EventController.class)).isEmpty();
    }
}
