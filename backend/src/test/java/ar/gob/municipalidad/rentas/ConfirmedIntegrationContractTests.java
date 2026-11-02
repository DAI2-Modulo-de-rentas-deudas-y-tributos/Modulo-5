package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test") @SpringBootTest @Transactional
class ConfirmedIntegrationContractTests {
    private static final Map<String,UUID> M2_EVENT_IDS=new HashMap<>();
    @Autowired ObjectMapper json;@Autowired ConfirmedM1Consumer m1;@Autowired ConfirmedM2Consumer m2;@Autowired ConfirmedM8Consumer m8;
    @Autowired ConfirmedIntegrationOutbox producer;@Autowired TaxpayerRepository taxpayers;@Autowired TicketCaseRepository tickets;
    @Autowired TicketCaseUpdateRepository ticketUpdates;@Autowired SocialBenefitRepository benefits;@Autowired TaxpayerRepresentationRepository representations;
    @Autowired IntegrationEventLogRepository logs;@Autowired ProcessedEventRepository processed;@Autowired IntegrationReprocessService reprocess;
    @Autowired TicketService ticketService;@Autowired OutboxRepository outbox;
    @Autowired EntityManager entityManager;

    @Test void m1RegisteredDeserializesMapsAndIsIdempotent() throws Exception {
        var event=citizen("M1-EVT-REGISTERED","REGISTERED","""
            {"dni":"34567890","cuil":"20345678901","name":"Marcos","lastname":"Ferreyra","birthdate":"1990-04-12","state":"ACTIVE","address":{"propertyId":5501}}
            """);
        TaxpayerReference first=m1.consumeCitizen(event);TaxpayerReference duplicate=m1.consumeCitizen(event);
        assertThat(first.id).isEqualTo(duplicate.id);assertThat(first.externalId).isEqualTo("8452");assertThat(first.displayName).isEqualTo("Marcos Ferreyra");assertThat(taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,"8452")).isPresent();assertThat(processed.existsByExternalEventId("M1-EVT-REGISTERED")).isTrue();
    }

    @Test void m1BlockedUpdatesExistingReference() throws Exception {registerCitizen();TaxpayerReference result=m1.consumeCitizen(citizen("M1-EVT-BLOCKED","BLOCKED","{\"reason\":\"DOCUMENTACION_APOCRIFA\",\"reviewRequired\":true}"));assertThat(result.externalStatus).isEqualTo(TaxpayerStatus.BLOCKED);}
    @Test void m1DeceasedUpdatesExistingReference() throws Exception {registerCitizen();TaxpayerReference result=m1.consumeCitizen(citizen("M1-EVT-DECEASED","DECEASED","{\"deceasedAt\":\"2026-08-19\",\"source\":\"REGISTRO_CIVIL\"}"));assertThat(result.externalStatus).isEqualTo(TaxpayerStatus.DECEASED);}
    @Test void m1AddressUpdatedIsAcceptedWithoutEconomicSideEffects() throws Exception {TaxpayerReference taxpayer=registerCitizen();long before=taxpayers.count();TaxpayerReference result=m1.consumeCitizen(citizen("M1-EVT-ADDRESS","ADDRESS_UPDATED","{\"previousAddress\":{},\"newAddress\":{\"street\":\"Nueva\"}}"));assertThat(result.id).isEqualTo(taxpayer.id);assertThat(taxpayers.count()).isEqualTo(before);assertThat(logs.findFirstByExternalEventIdOrderByIdDesc("M1-EVT-ADDRESS").orElseThrow().status).isEqualTo(IntegrationEventStatus.PROCESSED);}

    @Test void m1OrganizationRegisteredUsesExternalReferenceInsteadOfInternalPk() throws Exception {
        var event=json.readValue("""
            {"eventId":"M1-EVT-ORG","occurredAt":"2026-08-21T10:15:00-03:00","eventType":"organizationRegistered","producer":{"moduleId":"M1","service":"organizations-api"},"subject":"organizations/3311","data":{"cuit":3311,"taxId":"30712345678","legalName":"Panadería Del Sol S.R.L.","tradeName":"Panadería Del Sol","type":"COMMERCE","status":"ACTIVE","holder":{"personId":8452,"personType":"LEGAL"}}}
            """,ConfirmedInboundEvents.M1OrganizationRegisteredEvent.class);
        TaxpayerReference result=m1.consumeOrganization(event);assertThat(result.taxpayerType).isEqualTo(TaxpayerType.ORGANIZATION);assertThat(result.externalId).isEqualTo("3311");assertThat(result.cuit).isEqualTo("30712345678");
    }

    @Test void m1RepresentationGrantAndExpiryPreserveHistory() throws Exception {
        var granted=representation("M1-EVT-REP-GRANTED","representationGranted","{\"representationId\":990,\"personId\":8452,\"cuit\":3311,\"scope\":\"LEGAL_REPRESENTATIVE\",\"from\":\"2026-08-21\",\"status\":\"VALID\"}");
        TaxpayerRepresentationReference first=m1.consumeRepresentation(granted);var expired=representation("M1-EVT-REP-EXPIRED","representationExpired","{\"representationId\":990,\"personId\":8452,\"cuit\":3311,\"until\":\"2026-09-14\",\"status\":\"EXPIRED\"}");TaxpayerRepresentationReference result=m1.consumeRepresentation(expired);
        assertThat(result.id).isEqualTo(first.id);assertThat(result.status).isEqualTo("EXPIRED");assertThat(result.validUntil).isEqualTo(LocalDate.of(2026,9,14));assertThat(representations.count()).isEqualTo(1);
    }

    @Test void m2RoutedToM5CreatesCaseFromSnapshotAndDuplicateDoesNotDuplicate() throws Exception {
        var event=ticket("M2-EVT-ROUTED","M5",3881L,false,"ROUTED","ROUTED",routingDetails());TicketCase first=m2.consume(event);TicketCase duplicate=m2.consume(event);
        assertThat(first.id).isEqualTo(duplicate.id);assertThat(first.externalTicketId).isEqualTo("550e8400-e29b-41d4-a716-446655440000");assertThat(first.publicId).isEqualTo("TK-2026-000123");assertThat(first.correlationId).isEqualTo(event.correlationId().toString());assertThat(first.lastSourceEventId).isEqualTo(event.eventId().toString());assertThat(first.category).isEqualTo("Pagos y deudas");assertThat(first.description).isEqualTo("El ciudadano informa que abonó pero el pago no figura.");assertThat(tickets.count()).isEqualTo(1);
        entityManager.flush();entityManager.clear();
        TicketCase reloaded=tickets.findByExternalTicketId(first.externalTicketId).orElseThrow();
        assertThat(reloaded.requestType).isEqualTo("Informar un pago no registrado");
        assertThat(reloaded.category).isEqualTo("Pagos y deudas");
        assertThat(reloaded.subcategory).isEqualTo("Pagos");
        assertThat(ApiResponses.of(reloaded).requestType()).isEqualTo(reloaded.requestType);
        assertThat(ApiResponses.of(reloaded).subcategory()).isEqualTo(reloaded.subcategory);
    }

    @Test void m2TicketForAnotherAreaIsIgnoredWithoutPersistingBusinessContent() throws Exception {
        var event=ticket("M2-EVT-OTHER","M4",3881L,false,"ROUTED","ROUTED",routingDetails());assertThat(m2.consume(event)).isNull();assertThat(tickets.count()).isZero();IntegrationEventLog log=logs.findFirstByExternalEventIdOrderByIdDesc(event.eventId().toString()).orElseThrow();assertThat(log.status).isEqualTo(IntegrationEventStatus.IGNORED);assertThat(log.payload).isEqualTo("{}");
    }

    @Test void m2InformationProvidedDoesNotRequireInventedRequestId() throws Exception {
        m2.consume(ticket("M2-EVT-BASE","M5",3881L,false,"ROUTED","ROUTED",routingDetails()));JsonNode details=json.readTree("{\"informationResponse\":{\"message\":\"Adjunto el comprobante solicitado.\"}}");m2.consume(change(ticket("M2-EVT-INFO","M5",3881L,false,"INFORMATION_PROVIDED","IN_PROGRESS",details),"2026-08-24T19:11:00-03:00"));assertThat(ticketUpdates.findByTicketCaseIdOrderByCreatedAt(tickets.findByExternalTicketId("550e8400-e29b-41d4-a716-446655440000").orElseThrow().id)).singleElement().extracting(x->x.createdBy).isEqualTo("M2");
    }

    @Test void m2AnonymousTicketAllowsNullCitizenId() throws Exception {
        TicketCase result=m2.consume(ticket("M2-EVT-ANON","M5",null,true,"ROUTED","ROUTED",routingDetails()));assertThat(result.externalCitizenId).isNull();assertThat(result.taxpayerId).isNull();
    }

    @Test void m2CoreRetryUsesConfirmedConsumerRatherThanNumericLegacyAdapter() throws Exception {
        var event=ticket("M2-EVT-RETRY","M5",null,true,"ROUTED","ROUTED",routingDetails());
        IntegrationEventLog failed=new IntegrationEventLog();failed.eventId=event.eventId();failed.externalEventId=event.eventId().toString();failed.eventType="ticketUpdated";failed.sourceModule="atencion-ciudadana";failed.direction=EventDirection.INBOUND;failed.status=IntegrationEventStatus.FAILED;failed.payload=json.writeValueAsString(event);failed.retryCount=0;failed.occurredAt=event.occurredAt();failed.receivedAt=OffsetDateTime.now();logs.save(failed);
        assertThat(reprocess.reprocess(event.eventId().toString(),"Reintento QA").status).isEqualTo(IntegrationEventStatus.PROCESSED);
        assertThat(tickets.findByExternalTicketId(event.data().ticketId().toString())).isPresent();
    }

    @Test void m2ReclassificationIsIgnoredEvenForM5() throws Exception {
        var event=ticket("M2-EVT-RECLASS","M5",3881L,false,"INFORMATION_PROVIDED","IN_REVIEW",
            json.readTree("{\"informationResponse\":{\"purpose\":\"RECLASSIFICATION\",\"message\":\"Datos nuevos\"}}"));
        assertThat(m2.consume(event)).isNull();
        assertThat(tickets.count()).isZero();
        assertThat(logs.findFirstByExternalEventIdOrderByIdDesc(event.eventId().toString()).orElseThrow().payload).isEqualTo("{}");
    }

    @Test void m2RoutedRequiresCompleteClassification() throws Exception {
        var event=ticket("M2-EVT-INCOMPLETE","M5",3881L,false,"ROUTED","ROUTED",
            json.readTree("{\"routing\":{\"requestType\":\"Informar pago\",\"description\":\"Falta subcategoría\"}}"));
        assertThatThrownBy(()->m2.consume(event)).isInstanceOf(BusinessException.class).hasMessageContaining("details.routing.category");
        assertThat(tickets.count()).isZero();
    }

    @Test void m2ReassignmentUpdatesSameTicketAndClearsAssignment() throws Exception {
        TicketCase first=m2.consume(ticket("M2-EVT-INITIAL","M5",3881L,false,"ROUTED","ROUTED",routingDetails()));
        first.assignedTo="empleado.anterior";tickets.save(first);
        JsonNode rerouting=json.readTree("{\"routing\":{\"requestType\":\"Consulta por deuda\",\"category\":\"Tributos\",\"subcategory\":\"Deudas\",\"ticketType\":\"INQUIRY\",\"summary\":\"Saldo\",\"description\":\"Solicita detalle de saldo\",\"reassignment\":true}}");
        TicketCase updated=m2.consume(change(ticket("M2-EVT-REASSIGNED","M5",3881L,false,"ROUTED","ROUTED",rerouting),"2026-08-24T19:11:00-03:00"));
        assertThat(updated.id).isEqualTo(first.id);assertThat(updated.externalTicketId).isEqualTo(first.externalTicketId);
        assertThat(updated.category).isEqualTo("Tributos");assertThat(updated.assignedTo).isNull();assertThat(tickets.count()).isEqualTo(1);
        entityManager.flush();entityManager.clear();
        TicketCase reloaded=tickets.findById(first.id).orElseThrow();
        assertThat(reloaded.requestType).isEqualTo("Consulta por deuda");
        assertThat(reloaded.category).isEqualTo("Tributos");
        assertThat(reloaded.subcategory).isEqualTo("Deudas");
    }

    @Test void m2AdministrativeStatusAndOlderEventCannotReopenOrDuplicateUpdates() throws Exception {
        TicketCase first=m2.consume(ticket("M2-EVT-BASE-ORDER","M5",3881L,false,"ROUTED","ROUTED",routingDetails()));
        TicketCase duplicate=m2.consume(change(ticket("M2-EVT-DUPLICATE","M5",3881L,false,"DUPLICATE_LINKED","DUPLICATE",null),"2026-08-24T19:12:00-03:00"));
        assertThat(duplicate.status).isEqualTo(TicketCaseStatus.DUPLICATE);
        var stale=change(ticket("M2-EVT-STALE","M5",3881L,false,"INFORMATION_PROVIDED","IN_PROGRESS",
            json.readTree("{\"informationResponse\":{\"message\":\"Respuesta atrasada\"}}")),"2026-08-24T19:11:00-03:00");
        assertThat(m2.consume(stale).status).isEqualTo(TicketCaseStatus.DUPLICATE);
        assertThat(ticketUpdates.findByTicketCaseIdOrderByCreatedAt(first.id)).isEmpty();
        assertThatThrownBy(()->ticketService.assign(first.id)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("no admite esta operación");
    }

    @ParameterizedTest @ValueSource(strings={"STARTED","PROGRESS","INFORMATION_REQUIRED","RETURNED","RESOLVED","REJECTED"})
    void m2OutboundCarriesOnlyUpdateTypeNotCanonicalStatus(String updateType) throws Exception {
        UUID actor=UUID.fromString("3b9f1c52-6a7e-4d08-9c41-5e2a8d7f0b13");
        JsonNode details=switch(updateType){
            case "INFORMATION_REQUIRED"->json.readTree("{\"informationRequest\":{\"messageForCitizen\":\"Aporte comprobante\"}}");
            case "RETURNED"->json.readTree("{\"returnInfo\":{\"reasonCode\":\"REQUEST_TYPE_MISMATCH\"}}");
            case "RESOLVED"->json.readTree("{\"resolution\":{\"type\":\"ACTION_COMPLETED\"}}");
            case "REJECTED"->json.readTree("{\"cancellation\":{\"reasonCode\":\"REJECTED_BY_AREA\"}}");
            default->null;
        };
        JsonNode tree=json.valueToTree(producer.ticketStatus("550e8400-e29b-41d4-a716-446655440000",updateType,"mensaje","interno",details,actor,"294e7015-0e6a-4bd1-8240-ce3148fd937d",null));assertThat(tree.path("eventType").asText()).isEqualTo("updateTicketStatus");assertThat(tree.path("data").path("updateType").asText()).isEqualTo(updateType);assertThat(tree.path("data").path("updatedBy").path("type").asText()).isEqualTo("EXTERNAL_USER");assertThat(tree.path("data").path("updatedBy").path("id").asText()).isEqualTo(actor.toString());assertThat(tree.path("data").path("updateOccurredAt").isTextual()).isTrue();assertThat(tree.path("data").has("status")).isFalse();assertThat(tree.path("data").has("newStatus")).isFalse();assertThat(tree.path("data").has("previousStatus")).isFalse();
    }

    @Test void m2OutboundRejectsMissingHumanUuidAndInvalidVariant() throws Exception {
        String id="550e8400-e29b-41d4-a716-446655440000";
        assertThatThrownBy(()->producer.ticketStatus(id,"STARTED","Iniciado",null,null,(UUID)null,"294e7015-0e6a-4bd1-8240-ce3148fd937d",null)).isInstanceOf(BusinessException.class).hasMessageContaining("UUID de integración");
        assertThatThrownBy(()->producer.ticketStatus(id,"RESOLVED",null,null,json.readTree("{\"resolution\":{\"type\":\"ACTION_COMPLETED\"}}"),UUID.randomUUID(),"294e7015-0e6a-4bd1-8240-ce3148fd937d",null)).isInstanceOf(BusinessException.class).hasMessageContaining("publicMessage");
        JsonNode system=json.valueToTree(producer.systemTicketStatus(id,"STARTED","Inicio automático",null,null,"294e7015-0e6a-4bd1-8240-ce3148fd937d",null));
        assertThat(system.path("data").path("updatedBy").path("type").asText()).isEqualTo("SYSTEM");
        assertThat(system.path("data").path("updatedBy").path("id").isNull()).isTrue();
    }

    @Test void m2TicketOperationTakesActorUuidFromAuthenticatedBackendIdentity() throws Exception {
        TicketCase ticket=m2.consume(ticket("M2-EVT-ACTOR","M5",null,true,"ROUTED","ROUTED",routingDetails()));
        UUID actor=UUID.fromString("3b9f1c52-6a7e-4d08-9c41-5e2a8d7f0b13");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("empleado.m5",null,actor),null,List.of(new SimpleGrantedAuthority("ROLE_RENTAS"))));
        try{
            ticketService.assign(ticket.id);
            OutboxEvent event=outbox.findAll().stream().filter(x->"updateTicketStatus".equals(x.eventType)).findFirst().orElseThrow();
            JsonNode payload=json.readTree(event.payload);
            assertThat(payload.path("data").path("updatedBy").path("id").asText()).isEqualTo(actor.toString());
            assertThat(payload.path("data").path("updatedBy").path("id").asText()).isNotEqualTo("empleado.m5");
            assertThat(payload.path("correlationId").asText()).isEqualTo(ticket.correlationId);
            assertThat(payload.path("causationId").asText()).isEqualTo(ticket.lastSourceEventId);
        }finally{SecurityContextHolder.clearContext();}
    }

    @Test void m2OutboundPreservesIncomingCorrelationAndCausation(){
        String correlation="294e7015-0e6a-4bd1-8240-ce3148fd937d",cause="ceb59c25-f422-4a2d-97f1-8d4143b8c89f";
        JsonNode tree=json.valueToTree(producer.ticketStatus("550e8400-e29b-41d4-a716-446655440000","STARTED","Iniciado",null,null,UUID.fromString("3b9f1c52-6a7e-4d08-9c41-5e2a8d7f0b13"),correlation,cause));
        assertThat(tree.path("correlationId").asText()).isEqualTo(correlation);
        assertThat(tree.path("causationId").asText()).isEqualTo(cause);
    }

    @ParameterizedTest @ValueSource(strings={"APPROVED","SUSPENDED","FINALIZED"})
    void m8ConfirmedStatusesAreStoredSeparatelyFromCalculatedState(String status) throws Exception {
        SocialBenefitReference result=m8.consume(benefit("M8-EVT-"+status,status));assertThat(result.externalStatus).isEqualTo(status);assertThat(result.calculatedStatus).isEqualTo(expectedCalculatedStatus(status));assertThat(result.benefitsPayload).contains("TAX_EXEMPTION","benefitTypeId","programEditionId");
    }

    @Test void m8DuplicateBenefitUpdateHasOneEffect() throws Exception {var event=benefit("M8-EVT-DUP","APPROVED");SocialBenefitReference first=m8.consume(event);SocialBenefitReference duplicate=m8.consume(event);assertThat(duplicate.id).isEqualTo(first.id);assertThat(benefits.count()).isEqualTo(1);}

    @Test void m8CoreRetryUsesOfficialConsumerAndKeepsExternalUuid() throws Exception {
        var event=benefit("M8-EVT-RETRY","APPROVED");
        IntegrationEventLog failed=new IntegrationEventLog();failed.eventId=event.eventId();failed.externalEventId=event.eventId().toString();
        failed.eventType="socialBenefitUpdated";failed.sourceModule="desarrollo-social";failed.direction=EventDirection.INBOUND;
        failed.status=IntegrationEventStatus.FAILED;failed.payload=json.writeValueAsString(event);failed.retryCount=0;
        failed.occurredAt=event.occurredAt();failed.receivedAt=OffsetDateTime.now();logs.save(failed);
        assertThat(reprocess.reprocess(event.eventId().toString(),"Reintento M8").status).isEqualTo(IntegrationEventStatus.PROCESSED);
        assertThat(benefits.findByExternalBenefitId(event.data().benefitId().toString())).isPresent();
    }

    @Test void simpleOutboundEconomicContractsKeepConfirmedShapes() {
        var exemption=new ConfirmedOutboundEvents.ExemptionRequestedPayload(UUID.fromString("e5dd27f4-c803-4d5c-a75e-c003d99cc5af"),UUID.fromString("2808fa6c-1e50-4f71-a46e-ca444458924b"),"TASA_SERVICIOS","Situación socioeconómica",new BigDecimal("100.00"),LocalDate.parse("2026-09-01"),LocalDate.parse("2027-08-31"));JsonNode exemptionTree=json.valueToTree(producer.exemptionRequestedEvent(exemption));assertThat(exemptionTree.path("occurredAt").isTextual()).isTrue();assertThat(exemptionTree.path("sourceModule").asText()).isEqualTo("rentas");assertThat(exemptionTree.path("data").path("requestedPercentage").decimalValue()).isEqualByComparingTo("100.00");
        var plan=new ConfirmedOutboundEvents.PaymentPlanRequestedPayload(UUID.fromString("e5dd27f4-c803-4d5c-a75e-c003d99cc5af"),UUID.fromString("2808fa6c-1e50-4f71-a46e-ca444458924b"),List.of(UUID.fromString("a2fd68c2-b186-4e78-aa2b-191e241f584d"),UUID.fromString("653d9e79-2cd1-4ec1-8ada-845374f6903d")),new BigDecimal("200000.00"),6);JsonNode planTree=json.valueToTree(producer.paymentPlanRequestedEvent(plan));assertThat(planTree.path("data").path("debtIds")).hasSize(2);
        var overdue=new ConfirmedOutboundEvents.OverdueDebtPayload(UUID.fromString("653d9e79-2cd1-4ec1-8ada-845374f6903d"),UUID.fromString("2808fa6c-1e50-4f71-a46e-ca444458924b"),"TASA_SERVICIOS",new BigDecimal("85000.00"),LocalDate.parse("2026-08-20"));assertThat(json.valueToTree(producer.overdueDebtEvent(overdue)).path("eventType").asText()).isEqualTo("debtOverdue");
        var settled=new ConfirmedOutboundEvents.DebtSettledPayload(UUID.fromString("a2fd68c2-b186-4e78-aa2b-191e241f584d"),UUID.fromString("2808fa6c-1e50-4f71-a46e-ca444458924b"),"TASA_SERVICIOS","MUNICIPAL_TAX","liq-1",null,OffsetDateTime.parse("2026-08-27T14:00:00-03:00"),BigDecimal.ZERO.setScale(2),BigDecimal.ZERO.setScale(2));assertThat(json.valueToTree(producer.debtSettledEvent(settled)).path("data").path("outstandingBalance").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test void unifiedStatusEventsSerializeApprovedRejectedGrantedAndRejectedShapes() {
        var approved=new ConfirmedOutboundEvents.UpdateExemptionStatusPayload(UUID.fromString("e5dd27f4-c803-4d5c-a75e-c003d99cc5af"),UUID.fromString("2808fa6c-1e50-4f71-a46e-ca444458924b"),"APPROVED",UUID.fromString("f0c9ec39-d07e-46cf-b379-eb7f57488fa5"),"TASA",new BigDecimal("100"),LocalDate.now(),LocalDate.now().plusYears(1),null);var rejected=new ConfirmedOutboundEvents.UpdateExemptionStatusPayload(UUID.fromString("71110654-776a-4342-bc6c-44408c3be39e"),UUID.fromString("2808fa6c-1e50-4f71-a46e-ca444458924b"),"REJECTED",null,"TASA",null,null,null,"No cumple");JsonNode approvedTree=json.valueToTree(producer.exemptionStatusEvent(approved));JsonNode rejectedTree=json.valueToTree(producer.exemptionStatusEvent(rejected));assertThat(approvedTree.path("eventType").asText()).isEqualTo("updateExemptionStatus");assertThat(approvedTree.path("data").path("status").asText()).isEqualTo("APPROVED");assertThat(rejectedTree.path("data").path("status").asText()).isEqualTo("REJECTED");
        UUID request=UUID.fromString("e5dd27f4-c803-4d5c-a75e-c003d99cc5af"),citizen=UUID.fromString("2808fa6c-1e50-4f71-a46e-ca444458924b");var granted=new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload(request,citizen,"GRANTED",UUID.fromString("a2fd68c2-b186-4e78-aa2b-191e241f584d"),6,new BigDecimal("220000"),null);var planRejected=new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload(request,citizen,"REJECTED",null,null,null,"No elegible");assertThat(json.valueToTree(producer.paymentPlanStatusEvent(granted)).path("data").path("status").asText()).isEqualTo("GRANTED");assertThat(json.valueToTree(producer.paymentPlanStatusEvent(planRejected)).path("data").path("status").asText()).isEqualTo("REJECTED");
    }

    @Test void paymentPlanPayloadsRejectIncompleteOrNonPositiveValues(){
        UUID id=UUID.randomUUID();
        assertThatThrownBy(()->new ConfirmedOutboundEvents.PaymentPlanRequestedPayload(id,id,List.of(),BigDecimal.ONE,3))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ConfirmedOutboundEvents.PaymentPlanRequestedPayload(id,id,List.of(id),BigDecimal.ZERO,3))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload(id,id,"GRANTED",null,3,BigDecimal.TEN,null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload(id,id,"REJECTED",id,null,null,"No corresponde"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void m8OutboundPayloadsRejectMissingIdentityAndIncompleteExemptionDecisions() {
        UUID citizen=UUID.fromString("2808fa6c-1e50-4f71-a46e-ca444458924b");
        UUID request=UUID.fromString("e5dd27f4-c803-4d5c-a75e-c003d99cc5af");
        LocalDate from=LocalDate.of(2026,10,1);
        assertThatThrownBy(() -> new ConfirmedOutboundEvents.OverdueDebtPayload(request,null,"TASA",BigDecimal.TEN,from))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfirmedOutboundEvents.ExemptionRequestedPayload(request,citizen,"TASA","Motivo",new BigDecimal("101"),from,from))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfirmedOutboundEvents.UpdateExemptionStatusPayload(request,citizen,"APPROVED",null,"TASA",new BigDecimal("50"),from,null,null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfirmedOutboundEvents.UpdateExemptionStatusPayload(request,citizen,"REJECTED",null,"TASA",null,null,null,null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private TaxpayerReference registerCitizen() throws Exception {return m1.consumeCitizen(citizen("M1-EVT-REGISTER-"+System.nanoTime(),"REGISTERED","{\"dni\":\"34567890\",\"cuil\":\"20345678901\",\"name\":\"Marcos\",\"lastname\":\"Ferreyra\",\"birthdate\":\"1990-04-12\",\"state\":\"ACTIVE\"}"));}
    private ConfirmedInboundEvents.M1CitizenUpdatedEvent citizen(String id,String updateType,String details) throws Exception {return json.readValue("{\"eventId\":\""+id+"\",\"occurredAt\":\"2026-08-20T11:45:00-03:00\",\"eventType\":\"citizenUpdated\",\"producer\":{\"moduleId\":\"M1\",\"service\":\"citizens-api\"},\"subject\":\"citizens/8452\",\"data\":{\"citizenId\":8452,\"updateType\":\""+updateType+"\",\"details\":"+details+",\"updatedBy\":{\"type\":\"AREA_USER\",\"id\":\"USR-M1-14\"}}}",ConfirmedInboundEvents.M1CitizenUpdatedEvent.class);}
    private ConfirmedInboundEvents.M1RepresentationEvent representation(String id,String type,String data) throws Exception {return json.readValue("{\"eventId\":\""+id+"\",\"occurredAt\":\"2026-08-21T10:15:00-03:00\",\"eventType\":\""+type+"\",\"producer\":{\"moduleId\":\"M1\",\"service\":\"organizations-api\"},\"subject\":\"representations/990\",\"data\":"+data+"}",ConfirmedInboundEvents.M1RepresentationEvent.class);}
    private ConfirmedInboundEvents.M2TicketUpdatedEvent ticket(String id,String area,Long citizen,boolean anonymous,String update,String status,JsonNode details) throws Exception {String citizenJson=citizen==null?"null":"\""+UUID.nameUUIDFromBytes(("citizen-"+citizen).getBytes())+"\"";String eventId=M2_EVENT_IDS.computeIfAbsent(id,unused->UUID.randomUUID()).toString();return json.readValue("{\"eventId\":\""+eventId+"\",\"eventType\":\"ticketUpdated\",\"eventVersion\":\"1.0\",\"occurredAt\":\"2026-08-24T19:00:00-03:00\",\"sourceModule\":\"atencion-ciudadana\",\"correlationId\":\"294e7015-0e6a-4bd1-8240-ce3148fd937d\",\"data\":{\"ticketId\":\"550e8400-e29b-41d4-a716-446655440000\",\"publicId\":\"TK-2026-000123\",\"citizenId\":"+citizenJson+",\"isAnonymous\":"+anonymous+",\"responsibleAreaId\":\""+area+"\",\"updateType\":\""+update+"\",\"currentStatus\":\""+status+"\",\"currentPriority\":\"HIGH\",\"publicMessage\":\"El ciudadano aportó información.\",\"details\":"+details+",\"attachments\":[],\"updatedAt\":\"2026-08-24T19:10:00-03:00\"}}",ConfirmedInboundEvents.M2TicketUpdatedEvent.class);}
    private JsonNode routingDetails() throws Exception {return json.readTree("{\"routing\":{\"requestType\":\"Informar un pago no registrado\",\"category\":\"Pagos y deudas\",\"subcategory\":\"Pagos\",\"ticketType\":\"COMPLAINT\",\"summary\":\"Pago no registrado\",\"description\":\"El ciudadano informa que abonó pero el pago no figura.\",\"formData\":{},\"location\":null,\"resolutionDueAt\":\"2026-08-30T19:00:00-03:00\",\"escalation\":{\"active\":false}}}");}
    private ConfirmedInboundEvents.M2TicketUpdatedEvent change(ConfirmedInboundEvents.M2TicketUpdatedEvent event,String at){
        var data=event.data();var updated=new ConfirmedInboundEvents.M2TicketUpdatedData(data.ticketId(),data.publicId(),data.citizenId(),data.isAnonymous(),data.responsibleAreaId(),data.updateType(),data.currentStatus(),data.currentPriority(),data.progress(),data.publicMessage(),data.details(),data.attachments(),OffsetDateTime.parse(at));
        return new ConfirmedInboundEvents.M2TicketUpdatedEvent(event.eventId(),event.eventType(),event.eventVersion(),event.occurredAt(),event.sourceModule(),event.correlationId(),event.causationId(),updated);
    }
    private ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent benefit(String id,String status) throws Exception {
        String eventId=UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String startDate="APPROVED".equals(status)?"\"2026-09-01\"":"null";
        return json.readValue("{\"eventId\":\""+eventId+"\",\"eventType\":\"socialBenefitUpdated\",\"eventVersion\":\"1.0\",\"occurredAt\":\"2026-08-23T10:00:00-03:00\",\"sourceModule\":\"desarrollo-social\",\"data\":{\"benefitId\":\"a9866738-84db-4c10-acce-cfa78ec7485d\",\"citizenId\":\"a240d3e4-d143-4908-8797-ad0964aac6d5\",\"applicationId\":\"552eeb92-fc2b-4abe-b390-a9ca5f9f4bdc\",\"programId\":\"e19b76be-5ed6-49f4-9ae8-01c54ac7a34a\",\"programEditionId\":\"e6a13f58-2b9c-4d07-8e4f-7c5b1d9a0f26\",\"programName\":\"Subsidio Habitacional 2026\",\"benefitTypeId\":\"a0f3c1d8-2e4b-4c97-8d16-5b7e9f2a3c80\",\"type\":\"TAX_EXEMPTION\",\"description\":\"Exención de tasa\",\"amount\":null,\"status\":\""+status+"\",\"reason\":\"Actualización administrativa\",\"startDate\":"+startDate+",\"endDate\":\"2027-09-01\",\"updatedAt\":\"2026-08-23T10:00:00-03:00\"}}",ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent.class);
    }
    private SocialBenefitStatus expectedCalculatedStatus(String status){return switch(status){case "APPROVED"->SocialBenefitStatus.ACTIVE;case "SUSPENDED"->SocialBenefitStatus.SUSPENDED;case "FINALIZED"->SocialBenefitStatus.EXPIRED;default->throw new IllegalArgumentException(status);};}
}
