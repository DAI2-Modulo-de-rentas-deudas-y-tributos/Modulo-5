package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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
class CanonicalCitizenLinkTests {
    private static final UUID CANONICAL_ID=UUID.fromString("f398dbcb-a2b4-49af-a724-3529c6ddd0f6");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired TaxConceptRepository concepts;
    @Autowired CatalogService catalog;
    @Autowired ExemptionRequestRepository exemptionRequests;
    @Autowired OutboxRepository outbox;
    @Autowired AuditRepository audits;
    @Autowired DemoUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService demoAuth;
    @Autowired CoreInboundEventDispatcher inbound;

    private TaxpayerReference citizen;
    private DemoAuthSessions sessions;

    @BeforeEach void setUp() {
        citizen=new TaxpayerReference();
        citizen.taxpayerType=TaxpayerType.CITIZEN;
        citizen.externalId="4";
        citizen.displayName="Synthetic test fixture";
        citizen.externalStatus=TaxpayerStatus.ACTIVE;
        citizen.createdAt=citizen.updatedAt=OffsetDateTime.now();
        taxpayers.saveAndFlush(citizen);
        sessions=new DemoAuthSessions(users,taxpayers,encoder,demoAuth);
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void supervisorLinksOnlyTheConfirmedM1IdentityAndAuditsIt() throws Exception {
        String request=linkRequest(4,CANONICAL_ID,"M1-CASE-TEST-001");
        mvc.perform(post("/api/v1/taxpayers/{id}/canonical-citizen-link",citizen.id)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR))
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taxpayerId").value(citizen.id))
            .andExpect(jsonPath("$.m1CitizenId").value(4))
            .andExpect(jsonPath("$.canonicalCitizenId").value(CANONICAL_ID.toString()));

        assertThat(taxpayers.findById(citizen.id).orElseThrow().canonicalCitizenId).isEqualTo(CANONICAL_ID);
        var entry=audits.findAll().stream().filter(audit->audit.action.equals("CANONICAL_CITIZEN_ID_LINKED")).findFirst().orElseThrow();
        assertThat(entry.entityType).isEqualTo("SecurityEvent");
        assertThat(entry.userRole).isEqualTo("SUPERVISOR");
        assertThat(entry.newData).contains(citizen.id.toString(),"4",CANONICAL_ID.toString(),"M1-CASE-TEST-001");
    }

    @Test void repeatingTheSameLinkIsIdempotentAndDoesNotDuplicateTheAudit() throws Exception {
        String request=linkRequest(4,CANONICAL_ID,"M1-CASE-TEST-001");
        for(int i=0;i<2;i++) mvc.perform(post("/api/v1/taxpayers/{id}/canonical-citizen-link",citizen.id)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR))
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk());
        assertThat(audits.findAll().stream().filter(audit->audit.action.equals("CANONICAL_CITIZEN_ID_LINKED"))).hasSize(1);
    }

    @Test void mismatchedM1NumberDoesNotChangeTheLocalReference() throws Exception {
        mvc.perform(post("/api/v1/taxpayers/{id}/canonical-citizen-link",citizen.id)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR))
                .contentType(MediaType.APPLICATION_JSON).content(linkRequest(5,CANONICAL_ID,"M1-CASE-TEST-001")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("M1_CITIZEN_ID_MISMATCH"));
        assertThat(taxpayers.findById(citizen.id).orElseThrow().canonicalCitizenId).isNull();
    }

    @Test void organizationReferenceCannotReceiveACitizenIdentity() throws Exception {
        TaxpayerReference organization=new TaxpayerReference();
        organization.taxpayerType=TaxpayerType.ORGANIZATION;organization.externalId="4";organization.displayName="Synthetic test fixture";
        organization.externalStatus=TaxpayerStatus.ACTIVE;organization.createdAt=organization.updatedAt=OffsetDateTime.now();
        taxpayers.saveAndFlush(organization);
        mvc.perform(post("/api/v1/taxpayers/{id}/canonical-citizen-link",organization.id)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR))
                .contentType(MediaType.APPLICATION_JSON).content(linkRequest(4,CANONICAL_ID,"M1-CASE-TEST-001")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TAXPAYER_NOT_CITIZEN"));
        assertThat(taxpayers.findById(organization.id).orElseThrow().canonicalCitizenId).isNull();
    }

    @Test void canonicalUuidAlreadyLinkedElsewhereIsRejected() throws Exception {
        TaxpayerReference other=new TaxpayerReference();
        other.taxpayerType=TaxpayerType.CITIZEN;other.externalId="other-test-citizen";other.displayName="Synthetic test fixture";
        other.externalStatus=TaxpayerStatus.ACTIVE;other.canonicalCitizenId=CANONICAL_ID;other.createdAt=other.updatedAt=OffsetDateTime.now();
        taxpayers.saveAndFlush(other);
        mvc.perform(post("/api/v1/taxpayers/{id}/canonical-citizen-link",citizen.id)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR))
                .contentType(MediaType.APPLICATION_JSON).content(linkRequest(4,CANONICAL_ID,"M1-CASE-TEST-001")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANONICAL_CITIZEN_ID_ALREADY_LINKED"));
        assertThat(taxpayers.findById(citizen.id).orElseThrow().canonicalCitizenId).isNull();
    }

    @Test void differentExistingCanonicalUuidCannotBeReplaced() throws Exception {
        UUID prior=UUID.fromString("ca5287f4-e5d8-45d4-8cde-355bf42117a8");
        citizen.canonicalCitizenId=prior;taxpayers.saveAndFlush(citizen);
        mvc.perform(post("/api/v1/taxpayers/{id}/canonical-citizen-link",citizen.id)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR))
                .contentType(MediaType.APPLICATION_JSON).content(linkRequest(4,CANONICAL_ID,"M1-CASE-TEST-001")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANONICAL_CITIZEN_LINK_CONFLICT"));
        assertThat(taxpayers.findById(citizen.id).orElseThrow().canonicalCitizenId).isEqualTo(prior);
    }

    @Test void nonSupervisorCannotCreateCanonicalLink() throws Exception {
        mvc.perform(post("/api/v1/taxpayers/{id}/canonical-citizen-link",citizen.id)
                .header("X-Demo-Session",sessions.token(DemoRole.RENTAS))
                .contentType(MediaType.APPLICATION_JSON).content(linkRequest(4,CANONICAL_ID,"M1-CASE-TEST-001")))
            .andExpect(status().isForbidden());
        assertThat(taxpayers.findById(citizen.id).orElseThrow().canonicalCitizenId).isNull();
    }

    @Test void linkingDoesNotCreateAMissingTaxpayerReference() throws Exception {
        mvc.perform(post("/api/v1/taxpayers/{id}/canonical-citizen-link",Long.MAX_VALUE)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR))
                .contentType(MediaType.APPLICATION_JSON).content(linkRequest(4,CANONICAL_ID,"M1-CASE-TEST-001")))
            .andExpect(status().isNotFound());
        assertThat(taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,"4")).isPresent();
        assertThat(taxpayers.count()).isEqualTo(1);
    }

    @Test void linkedOwnerCreatesOneLocalLogExemptionEventWithStableIdentifiers() throws Exception {
        taxpayers.delete(citizen);
        taxpayers.flush();
        ObjectNode incoming=json.createObjectNode();
        incoming.put("eventId",UUID.randomUUID().toString());incoming.put("eventType","citizenUpdated");
        incoming.put("eventVersion","1.0");incoming.put("occurredAt",OffsetDateTime.now().toString());
        incoming.put("sourceModule","ciudadanos");incoming.put("correlationId",UUID.randomUUID().toString());incoming.putNull("causationId");
        ObjectNode data=incoming.putObject("data");data.put("citizenId",4);data.put("updateType","REGISTERED");
        ObjectNode details=data.putObject("details");details.put("dni","00000000");details.put("cuil","20000000004");
        details.put("name","Synthetic");details.put("lastname","Citizen");details.put("birthdate","1990-01-01");details.put("state","ACTIVE");details.putNull("address");
        data.putObject("updatedBy").put("type","SYSTEM").put("id","M1-TEST");
        inbound.dispatch(json.writeValueAsString(incoming));
        citizen=taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,"4").orElseThrow();

        mvc.perform(post("/api/v1/taxpayers/{id}/canonical-citizen-link",citizen.id)
                .header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR))
                .contentType(MediaType.APPLICATION_JSON).content(linkRequest(4,CANONICAL_ID,"M1-CASE-TEST-001")))
            .andExpect(status().isOk());

        TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
            "CANONICAL-LINK-TEST","Synthetic E2E test concept",null,TaxConceptType.FEE,"M5"));
        LocalDate today=LocalDate.now();
        var body=java.util.Map.of("taxpayerId",citizen.id,"taxConceptId",concept.id,"reason","Synthetic integration test",
            "percentage",new BigDecimal("50.00"),"validFrom",today,"validUntil",today.plusYears(1));
        String request=json.writeValueAsString(body),session=sessions.token(DemoRole.TAXPAYER,citizen.id);
        mvc.perform(post("/api/v1/exemption-requests").header("X-Demo-Session",session)
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));

        var created=exemptionRequests.findAll().stream().filter(item->item.taxpayerId.equals(citizen.id))
            .filter(item->item.taxConceptId.equals(concept.id)).findFirst().orElseThrow();
        var events=outbox.findAll().stream().filter(event->event.eventType.equals("exemptionRequested")
            &&event.aggregateId.equals(created.id.toString())).toList();
        assertThat(events).hasSize(1);
        var envelope=json.readTree(events.get(0).payload);
        assertThat(events.get(0).id.toString()).isEqualTo(envelope.path("eventId").asText());
        assertThat(envelope.path("eventType").asText()).isEqualTo("exemptionRequested");
        assertThat(envelope.path("eventVersion").asText()).isEqualTo("1.0");
        assertThat(envelope.path("sourceModule").asText()).isEqualTo("rentas");
        assertThat(UUID.fromString(envelope.path("correlationId").asText())).isNotNull();
        assertThat(envelope.path("data").path("requestId").asText()).isEqualTo(created.integrationId.toString());
        assertThat(envelope.path("data").path("citizenId").asText()).isEqualTo(CANONICAL_ID.toString());
        assertThat(envelope.path("data").path("conceptCode").asText()).isEqualTo("CANONICAL-LINK-TEST");
    }

    private String linkRequest(long m1CitizenId,UUID canonicalCitizenId,String confirmationReference) throws Exception {
        return json.writeValueAsString(new ApiDtos.LinkCanonicalCitizenRequest(m1CitizenId,canonicalCitizenId,confirmationReference));
    }
}
