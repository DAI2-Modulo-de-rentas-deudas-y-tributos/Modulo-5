package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:deferred-exemption-reconciliation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "rentas.outbox-delay-ms=3600000"})
@AutoConfigureMockMvc
class DeferredExemptionReconciliationTests {
    @Autowired CatalogService catalog;
    @Autowired ExemptionService exemptions;
    @Autowired DeferredExemptionReconciliation reconciliation;
    @Autowired ExemptionRequestRepository requests;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired OutboxRepository outbox;
    @Autowired IntegrationEventLogRepository logs;
    @Autowired AuditRepository audits;
    @Autowired OutboxPublisher publisher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired DemoUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService demoAuth;

    private TaxpayerReference taxpayer;
    private TaxConcept concept;

    @BeforeEach void setUp(){
        authenticate();
        String suffix=UUID.randomUUID().toString();
        taxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"M8-RECON-"+suffix,
            String.valueOf(Math.abs(suffix.hashCode())+10000000L),null,"Ciudadano prueba conciliación"));
        concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("M8_RECON_"+suffix.replace("-",""),
            "Tributo conciliación",null,TaxConceptType.FEE,"M5"));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void approvedDecisionSurvivesTransactionsAndReconcilesExactlyOnceWithPersistentUuids() throws Exception {
        ExemptionRequest request=createAndResolve(true);
        assertThat(events(request.id)).isEmpty();
        assertThat(marker("exemptionRequested",request.id).status).isEqualTo(IntegrationEventStatus.PENDING);
        assertThat(marker("updateExemptionStatus",request.id).status).isEqualTo(IntegrationEventStatus.PENDING);

        var stillMissing=reconciliation.reconcile(request.id);
        assertThat(stillMissing.outcome()).isEqualTo("CANONICAL_CITIZEN_ID_PENDING");
        assertThat(stillMissing.deliveryStatus()).isEqualTo("NOT_CONFIRMED_BY_CORE");
        assertThat(events(request.id)).isEmpty();
        assertThat(marker("exemptionRequested",request.id).retryCount).isEqualTo(1);

        UUID canonical=UUID.fromString("43e61e24-23a7-4f1a-8cc7-d83c17393506");
        taxpayer.canonicalCitizenId=canonical;
        taxpayers.saveAndFlush(taxpayer);
        String supervisor=new DemoAuthSessions(users,taxpayers,encoder,demoAuth).token(DemoRole.SUPERVISOR);
        mvc.perform(post("/api/v1/integrations/deferred/exemptions/{requestId}/reconcile",request.id)
                .header("X-Demo-Session",supervisor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("RECONCILED_LOCALLY"))
            .andExpect(jsonPath("$.requestDeferralStatus").value("PROCESSED"))
            .andExpect(jsonPath("$.resolutionDeferralStatus").value("PROCESSED"))
            .andExpect(jsonPath("$.deliveryStatus").value("NOT_CONFIRMED_BY_CORE"));

        OutboxEvent requested=event(request.id,"exemptionRequested");
        OutboxEvent resolved=event(request.id,"updateExemptionStatus");
        assertThat(requested.createdAt).isBeforeOrEqualTo(resolved.createdAt);
        JsonNode requestedData=json.readTree(requested.payload).path("data");
        JsonNode resolvedData=json.readTree(resolved.payload).path("data");
        assertThat(requestedData.path("requestId").asText()).isEqualTo(request.integrationId.toString());
        assertThat(requestedData.path("citizenId").asText()).isEqualTo(canonical.toString());
        assertThat(resolvedData.path("requestId").asText()).isEqualTo(request.integrationId.toString());
        assertThat(resolvedData.path("citizenId").asText()).isEqualTo(canonical.toString());
        assertThat(UUID.fromString(resolvedData.path("exemptionId").asText())).isNotNull();
        assertThat(marker("exemptionRequested",request.id).payload).contains(requested.id.toString()).contains("OUTBOX_PREPARED");
        assertThat(marker("updateExemptionStatus",request.id).payload).contains(resolved.id.toString()).contains("OUTBOX_PREPARED");

        assertThat(reconciliation.reconcile(request.id).outcome()).isEqualTo("NOTHING_PENDING");
        assertThat(events(request.id)).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_type='ExemptionRequest' and aggregate_id=?",
            Integer.class,request.id.toString())).isEqualTo(2);
        assertThat(audits.findByEntityTypeAndEntityIdOrderByOccurredAt("ExemptionRequest",request.id.toString()))
            .extracting(entry->entry.action).contains("DEFERRED_EXEMPTION_RECONCILED");
    }

    @Test void rejectedDecisionReconcilesInOrderWithoutApprovedOnlyFields() throws Exception {
        ExemptionRequest request=createAndResolve(false);
        taxpayer.canonicalCitizenId=UUID.fromString("1fd22afc-5757-4967-b795-4845d384cd42");
        taxpayers.saveAndFlush(taxpayer);

        var result=reconciliation.reconcile(request.id);
        assertThat(result.outcome()).isEqualTo("RECONCILED_LOCALLY");
        JsonNode rejected=json.readTree(event(request.id,"updateExemptionStatus").payload).path("data");
        assertThat(rejected.path("requestId").asText()).isEqualTo(request.integrationId.toString());
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("reason").asText()).isEqualTo("No acredita la condición");
        assertThat(rejected.has("exemptionId")).isFalse();
        assertThat(rejected.has("percentage")).isFalse();
        assertThat(events(request.id)).hasSize(2);
    }

    @Test void resolutionAfterTrustedLinkPreparesThePreviouslyDeferredRequestFirst(){
        ExemptionRequest request=exemptions.create(createRequest());
        taxpayer.canonicalCitizenId=UUID.fromString("5b4d1355-b77d-4335-9b8b-77c0c086758d");
        taxpayers.saveAndFlush(taxpayer);
        exemptions.start(request.id);
        exemptions.submit(request.id,"Análisis interno");
        TestActorSupport.asApprover(() -> exemptions.reject(request.id,"No acredita la condición"));

        assertThat(events(request.id)).extracting(event->event.eventType)
            .containsExactly("exemptionRequested","updateExemptionStatus");
        assertThat(marker("exemptionRequested",request.id).status).isEqualTo(IntegrationEventStatus.PROCESSED);
        assertThat(reconciliation.reconcile(request.id).outcome()).isEqualTo("NOTHING_PENDING");
    }

    @Test void legacyRequestOutboxRequiresReviewAndDoesNotDuplicateOrReleaseTheResolution(){
        ExemptionRequest request=createAndResolve(true);
        OutboxEvent legacy=new OutboxEvent();legacy.id=UUID.randomUUID();legacy.eventType="exemptionRequested";
        legacy.targetModule="CORE";legacy.aggregateType="ExemptionRequest";legacy.aggregateId=request.id.toString();
        legacy.payload="{\"data\":{\"requestId\":\"exr-"+request.id+"\",\"citizenId\":\"123\"}}";
        legacy.status=OutboxStatus.PENDING;legacy.retryCount=0;legacy.createdAt=OffsetDateTime.now();outbox.saveAndFlush(legacy);
        taxpayer.canonicalCitizenId=UUID.fromString("c3836fa8-d35f-4bd4-a21b-625bc28cf958");
        taxpayers.saveAndFlush(taxpayer);

        var result=reconciliation.reconcile(request.id);
        assertThat(result.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.requestDeferralReason()).isEqualTo("EXISTING_OUTBOX_CONTRACT_REVIEW_REQUIRED");
        assertThat(result.resolutionDeferralReason()).isEqualTo("REQUEST_EVENT_REVIEW_REQUIRED");
        assertThat(events(request.id)).hasSize(1);
        assertThat(marker("updateExemptionStatus",request.id).status).isEqualTo(IntegrationEventStatus.PENDING);
        publisher.publishPending();
        assertThat(outbox.findById(legacy.id).orElseThrow().status).isEqualTo(OutboxStatus.DEAD_LETTER);
    }

    @Test void outboxNeverProcessesResolutionBeforeRequestIsLocallyProcessed(){
        String aggregateId="outbox-order-"+UUID.randomUUID();
        OutboxEvent resolution=fakeOutbox("updateExemptionStatus",aggregateId);
        publisher.publishPending();
        assertThat(outbox.findById(resolution.id).orElseThrow().status).isEqualTo(OutboxStatus.PENDING);

        OutboxEvent request=fakeOutbox("exemptionRequested",aggregateId);
        publisher.publishPending();
        assertThat(outbox.findById(request.id).orElseThrow().status).isEqualTo(OutboxStatus.LOCAL_ONLY);
        assertThat(outbox.findById(resolution.id).orElseThrow().status).isEqualTo(OutboxStatus.PENDING);
        publisher.publishPending();
        assertThat(outbox.findById(resolution.id).orElseThrow().status).isEqualTo(OutboxStatus.LOCAL_ONLY);
    }

    @Test void controlledEndpointRejectsAnonymousAndOrdinaryStaff() throws Exception {
        ExemptionRequest request=exemptions.create(createRequest());
        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/integrations/deferred/exemptions/{requestId}/reconcile",request.id))
            .andExpect(status().isUnauthorized());
        String rentas=new DemoAuthSessions(users,taxpayers,encoder,demoAuth).token(DemoRole.RENTAS);
        mvc.perform(post("/api/v1/integrations/deferred/exemptions/{requestId}/reconcile",request.id)
                .header("X-Demo-Session",rentas))
            .andExpect(status().isForbidden());
    }

    private ExemptionRequest createAndResolve(boolean approved){
        ExemptionRequest request=exemptions.create(createRequest());
        exemptions.start(request.id);
        exemptions.submit(request.id,"Análisis interno");
        if(approved)TestActorSupport.asApprover(() -> exemptions.approve(request.id,null));
        else TestActorSupport.asApprover(() -> exemptions.reject(request.id,"No acredita la condición"));
        return requests.findById(request.id).orElseThrow();
    }
    private ApiDtos.CreateExemptionRequest createRequest(){
        return new ApiDtos.CreateExemptionRequest(taxpayer.id,concept.id,"Motivo social",new BigDecimal("50"),
            LocalDate.of(2026,10,1),LocalDate.of(2027,9,30),null);
    }
    private List<OutboxEvent> events(Long requestId){
        return outbox.findAll().stream().filter(event->"ExemptionRequest".equals(event.aggregateType)
            &&requestId.toString().equals(event.aggregateId)).toList();
    }
    private OutboxEvent event(Long requestId,String type){
        return events(requestId).stream().filter(candidate->type.equals(candidate.eventType)).findFirst().orElseThrow();
    }
    private IntegrationEventLog marker(String type,Long requestId){
        return logs.findFirstByBusinessKeyOrderByIdDesc("outbound-deferred:"+type+":ExemptionRequest:"+requestId).orElseThrow();
    }
    private OutboxEvent fakeOutbox(String type,String aggregateId){
        OutboxEvent event=new OutboxEvent();event.id=UUID.randomUUID();event.eventType=type;event.targetModule="CORE";
        event.aggregateType="ExemptionRequest";event.aggregateId=aggregateId;
        event.payload="{\"data\":{\"requestId\":\"6fe5c193-ad41-42db-a4d0-a90614fb4fb9\",\"citizenId\":\"4a9683e8-1279-4f62-98d4-2803ececab8f\",\"status\":\"REJECTED\"}}";
        event.status=OutboxStatus.PENDING;event.retryCount=0;event.createdAt=OffsetDateTime.now();
        return outbox.saveAndFlush(event);
    }
    private void authenticate(){
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("qa.reconciliation",null),null,
            List.of(new SimpleGrantedAuthority("ROLE_RENTAS"),new SimpleGrantedAuthority("ROLE_SUPERVISOR"))));
    }
}
