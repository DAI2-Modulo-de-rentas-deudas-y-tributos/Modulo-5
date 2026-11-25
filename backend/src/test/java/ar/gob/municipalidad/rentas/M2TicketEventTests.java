package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(properties =
    "spring.datasource.url=jdbc:h2:mem:m2-events;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@Transactional
class M2TicketEventTests {
    @Autowired MockMvc mvc;
    @Autowired TicketCaseRepository tickets;
    @Autowired IntegrationEventLogRepository eventLog;
    @Autowired IntegrationReprocessService reprocess;
    @Autowired DemoUserRepository demoUsers; @Autowired TaxpayerRepository taxpayers;
    @Autowired PasswordEncoder encoder; @Autowired DemoAuthService demoAuth;

    @Test void elEndpointDeEnvelopeLegadoYaNoEstaExpuesto() throws Exception {
        mvc.perform(post("/api/v1/dev/integrations/m2/events").headers(supervisor())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
    }

    @Test void elEndpointConfirmadoDeCoreSigueAceptandoTicketUpdatedIdempotente() throws Exception {
        UUID ticketId=UUID.randomUUID();
        long countBefore=tickets.count();
        String event="""
            {"eventId":"%s","eventType":"ticketUpdated","eventVersion":"1.0","occurredAt":"2026-11-20T10:00:00-03:00","sourceModule":"atencion-ciudadana","correlationId":"%s","data":{"ticketId":"%s","publicId":"TK-2026-TEST-772","citizenId":null,"isAnonymous":true,"responsibleAreaId":"M5","updateType":"ROUTED","currentStatus":"ROUTED","currentPriority":"HIGH","progress":null,"publicMessage":null,"details":{"routing":{"requestType":"Informar un pago no registrado","category":"Pagos y deudas","subcategory":"Pagos","ticketType":"COMPLAINT","summary":"Pago no registrado","description":"El ciudadano informa que abonó pero el pago no figura.","formData":{},"location":null,"resolutionDueAt":"2026-11-30T19:00:00-03:00","escalation":{"active":false}}},"attachments":[],"updatedAt":"2026-11-20T10:00:00-03:00"}}
            """.formatted(UUID.randomUUID(),UUID.randomUUID(),ticketId);

        for(int delivery=0;delivery<2;delivery++)
            mvc.perform(post("/api/v1/dev/integrations/m2/core-events").headers(supervisor())
                    .contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isAccepted());

        assertThat(tickets.findByExternalTicketId(ticketId.toString())).isPresent();
        assertThat(tickets.count()).isEqualTo(countBefore+1);
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED) void reintentaUnEventoFallidoHastaEnviarloALaColaDeErrores() {
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
        var headers=new org.springframework.http.HttpHeaders();
        headers.set("X-Demo-Session",new DemoAuthSessions(demoUsers,taxpayers,encoder,demoAuth).token(DemoRole.SUPERVISOR));
        return headers;
    }
}

@ActiveProfiles("test")
@SpringBootTest(properties = {
    "rentas.security.dev-mode=false",
    "spring.datasource.url=jdbc:h2:mem:m2-events-disabled;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class M2TicketEventSimulationDisabledTests {
    @Autowired ApplicationContext context;

    @Test
    void noExponeElSimuladorFueraDelModoDesarrollo() {
        assertThat(context.getBeansOfType(DevM2EventController.class)).isEmpty();
    }
}
