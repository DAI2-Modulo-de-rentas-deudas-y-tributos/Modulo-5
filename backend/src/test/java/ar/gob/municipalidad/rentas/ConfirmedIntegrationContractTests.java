package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test") @SpringBootTest @Transactional
class ConfirmedIntegrationContractTests {
    @Autowired ObjectMapper json;@Autowired ConfirmedM1Consumer m1;@Autowired ConfirmedM2Consumer m2;@Autowired ConfirmedM8Consumer m8;
    @Autowired ConfirmedIntegrationOutbox producer;@Autowired TaxpayerRepository taxpayers;@Autowired TicketCaseRepository tickets;
    @Autowired TicketCaseUpdateRepository ticketUpdates;@Autowired SocialBenefitRepository benefits;@Autowired TaxpayerRepresentationRepository representations;
    @Autowired IntegrationEventLogRepository logs;@Autowired ProcessedEventRepository processed;

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
        assertThat(first.id).isEqualTo(duplicate.id);assertThat(first.externalTicketId).isEqualTo("1042");assertThat(first.category).isEqualTo("Informar un pago no registrado");assertThat(first.description).isEqualTo("El ciudadano informa que abonó pero el pago no figura.");assertThat(tickets.count()).isEqualTo(1);
    }

    @Test void m2TicketForAnotherAreaIsIgnoredWithoutPersistingBusinessContent() throws Exception {
        assertThat(m2.consume(ticket("M2-EVT-OTHER","M4",3881L,false,"ROUTED","ROUTED",routingDetails()))).isNull();assertThat(tickets.count()).isZero();IntegrationEventLog log=logs.findFirstByExternalEventIdOrderByIdDesc("M2-EVT-OTHER").orElseThrow();assertThat(log.status).isEqualTo(IntegrationEventStatus.IGNORED);assertThat(log.payload).isEqualTo("{}");
    }

    @Test void m2InformationProvidedDoesNotRequireInventedRequestId() throws Exception {
        m2.consume(ticket("M2-EVT-BASE","M5",3881L,false,"ROUTED","ROUTED",routingDetails()));JsonNode details=json.readTree("{\"informationResponse\":{\"message\":\"Adjunto el comprobante solicitado.\"}}");m2.consume(ticket("M2-EVT-INFO","M5",3881L,false,"INFORMATION_PROVIDED","IN_PROGRESS",details));assertThat(ticketUpdates.findByTicketCaseIdOrderByCreatedAt(tickets.findByExternalTicketId("1042").orElseThrow().id)).singleElement().extracting(x->x.createdBy).isEqualTo("M2");
    }

    @Test void m2AnonymousTicketAllowsNullCitizenId() throws Exception {
        TicketCase result=m2.consume(ticket("M2-EVT-ANON","M5",null,true,"ROUTED","ROUTED",routingDetails()));assertThat(result.externalCitizenId).isNull();assertThat(result.taxpayerId).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"STARTED","INFORMATION_REQUIRED","RESOLVED","RETURNED"})
    void m2OutboundCarriesOnlyUpdateTypeNotCanonicalStatus(String updateType) {
        JsonNode tree=json.valueToTree(producer.ticketStatus(1042L,updateType,"mensaje","interno",json.createObjectNode(),"USR-M5-12"));assertThat(tree.path("eventType").asText()).isEqualTo("updateTicketStatus");assertThat(tree.path("data").path("updateType").asText()).isEqualTo(updateType);assertThat(tree.path("data").has("status")).isFalse();assertThat(tree.path("data").has("newStatus")).isFalse();assertThat(tree.path("data").has("previousStatus")).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={"APPROVED","REJECTED","SUSPENDED","FINALIZED"})
    void m8ConfirmedStatusesAreStoredSeparatelyFromCalculatedState(String status) throws Exception {
        SocialBenefitReference result=m8.consume(benefit("M8-EVT-"+status,status));assertThat(result.externalStatus).isEqualTo(status);assertThat(result.calculatedStatus).isEqualTo(expectedCalculatedStatus(status));assertThat(result.benefitsPayload).contains("TAX_EXEMPTION","FOOD_ASSISTANCE","UTILITY_SUBSIDY");
    }

    @Test void m8DuplicateBenefitUpdateHasOneEffect() throws Exception {var event=benefit("M8-EVT-DUP","APPROVED");SocialBenefitReference first=m8.consume(event);SocialBenefitReference duplicate=m8.consume(event);assertThat(duplicate.id).isEqualTo(first.id);assertThat(benefits.count()).isEqualTo(1);}

    @Test void simpleOutboundEconomicContractsKeepConfirmedShapes() {
        var exemption=new ConfirmedOutboundEvents.ExemptionRequestedPayload("exr-00600","c-00123","TASA_SERVICIOS","Situación socioeconómica",new BigDecimal("100.00"),LocalDate.parse("2026-09-01"),LocalDate.parse("2027-08-31"));JsonNode exemptionTree=json.valueToTree(producer.exemptionRequestedEvent(exemption));assertThat(exemptionTree.path("timestamp").isTextual()).isTrue();assertThat(exemptionTree.path("sourceModule").asText()).isEqualTo("rentas");assertThat(exemptionTree.path("payload").path("requestedPercentage").decimalValue()).isEqualByComparingTo("100.00");
        var plan=new ConfirmedOutboundEvents.PaymentPlanRequestedPayload("ppr-00800","c-00123",List.of("debt-03001","debt-03002"),new BigDecimal("200000.00"),6);JsonNode planTree=json.valueToTree(producer.paymentPlanRequestedEvent(plan));assertThat(planTree.path("payload").path("debtIds")).hasSize(2);
        var overdue=new ConfirmedOutboundEvents.OverdueDebtPayload("debt-03200","c-00123","TASA_SERVICIOS",new BigDecimal("85000.00"),LocalDate.parse("2026-08-20"));assertThat(json.valueToTree(producer.overdueDebtEvent(overdue)).path("eventType").asText()).isEqualTo("overdueDebt");
        var settled=new ConfirmedOutboundEvents.DebtSettledPayload("debt-03200","c-00123","TASA_SERVICIOS",OffsetDateTime.parse("2026-08-27T14:00:00-03:00"),BigDecimal.ZERO.setScale(2));assertThat(json.valueToTree(producer.debtSettledEvent(settled)).path("payload").path("outstandingBalance").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test void unifiedStatusEventsSerializeApprovedRejectedGrantedAndRejectedShapes() {
        var approved=new ConfirmedOutboundEvents.UpdateExemptionStatusPayload("exr-1","c-1","APPROVED","ex-1","TASA",new BigDecimal("100"),LocalDate.now(),LocalDate.now().plusYears(1),null);var rejected=new ConfirmedOutboundEvents.UpdateExemptionStatusPayload("exr-2","c-2","REJECTED",null,"TASA",null,null,null,"No cumple");JsonNode approvedTree=json.valueToTree(producer.exemptionStatusEvent(approved));JsonNode rejectedTree=json.valueToTree(producer.exemptionStatusEvent(rejected));assertThat(approvedTree.path("eventType").asText()).isEqualTo("updateExemptionStatus");assertThat(approvedTree.path("payload").path("status").asText()).isEqualTo("APPROVED");assertThat(rejectedTree.path("payload").path("status").asText()).isEqualTo("REJECTED");
        var granted=new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload("ppr-1","c-1","GRANTED","plan-1",6,new BigDecimal("220000"),null);var planRejected=new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload("ppr-2","c-2","REJECTED",null,null,null,"No elegible");assertThat(json.valueToTree(producer.paymentPlanStatusEvent(granted)).path("payload").path("status").asText()).isEqualTo("GRANTED");assertThat(json.valueToTree(producer.paymentPlanStatusEvent(planRejected)).path("payload").path("status").asText()).isEqualTo("REJECTED");
    }

    private TaxpayerReference registerCitizen() throws Exception {return m1.consumeCitizen(citizen("M1-EVT-REGISTER-"+System.nanoTime(),"REGISTERED","{\"dni\":\"34567890\",\"cuil\":\"20345678901\",\"name\":\"Marcos\",\"lastname\":\"Ferreyra\",\"birthdate\":\"1990-04-12\",\"state\":\"ACTIVE\"}"));}
    private ConfirmedInboundEvents.M1CitizenUpdatedEvent citizen(String id,String updateType,String details) throws Exception {return json.readValue("{\"eventId\":\""+id+"\",\"occurredAt\":\"2026-08-20T11:45:00-03:00\",\"eventType\":\"citizenUpdated\",\"producer\":{\"moduleId\":\"M1\",\"service\":\"citizens-api\"},\"subject\":\"citizens/8452\",\"data\":{\"citizenId\":8452,\"updateType\":\""+updateType+"\",\"details\":"+details+",\"updatedBy\":{\"type\":\"AREA_USER\",\"id\":\"USR-M1-14\"}}}",ConfirmedInboundEvents.M1CitizenUpdatedEvent.class);}
    private ConfirmedInboundEvents.M1RepresentationEvent representation(String id,String type,String data) throws Exception {return json.readValue("{\"eventId\":\""+id+"\",\"occurredAt\":\"2026-08-21T10:15:00-03:00\",\"eventType\":\""+type+"\",\"producer\":{\"moduleId\":\"M1\",\"service\":\"organizations-api\"},\"subject\":\"representations/990\",\"data\":"+data+"}",ConfirmedInboundEvents.M1RepresentationEvent.class);}
    private ConfirmedInboundEvents.M2TicketUpdatedEvent ticket(String id,String area,Long citizen,boolean anonymous,String update,String status,JsonNode details) throws Exception {String citizenJson=citizen==null?"null":citizen.toString();return json.readValue("{\"specVersion\":\"1.0\",\"eventId\":\""+id+"\",\"eventType\":\"ticketUpdated\",\"occurredAt\":\"2026-08-24T19:00:00-03:00\",\"producer\":{\"moduleId\":\"M2\",\"service\":\"help-center-api\"},\"subject\":\"tickets/1042\",\"data\":{\"ticketId\":1042,\"citizenId\":"+citizenJson+",\"isAnonymous\":"+anonymous+",\"responsibleAreaId\":\""+area+"\",\"updateType\":\""+update+"\",\"currentStatus\":\""+status+"\",\"currentPriority\":\"HIGH\",\"publicMessage\":\"El ciudadano aportó información.\",\"details\":"+details+",\"attachments\":[],\"updatedAt\":\"2026-08-24T19:10:00-03:00\"}}",ConfirmedInboundEvents.M2TicketUpdatedEvent.class);}
    private JsonNode routingDetails() throws Exception {return json.readTree("{\"routing\":{\"requestType\":{\"id\":31,\"name\":\"Informar un pago no registrado\"},\"ticketType\":\"COMPLAINT\",\"summary\":\"Pago no registrado\",\"description\":\"El ciudadano informa que abonó pero el pago no figura.\",\"formData\":{},\"location\":null,\"resolutionDueAt\":\"2026-08-30T19:00:00-03:00\",\"escalation\":{\"active\":false}}}");}
    private ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent benefit(String id,String status) throws Exception {return json.readValue("{\"eventId\":\""+id+"\",\"eventType\":\"socialBenefitUpdated\",\"timestamp\":\"2026-08-23T10:00:00-03:00\",\"sourceModule\":\"social-development\",\"payload\":{\"benefitId\":\"ben-00789\",\"citizenId\":\"c-00123\",\"applicationId\":\"sol-00456\",\"programId\":\"prog-00789\",\"programName\":\"Subsidio Habitacional 2026\",\"benefits\":[{\"type\":\"TAX_EXEMPTION\"},{\"type\":\"FOOD_ASSISTANCE\",\"amount\":15000},{\"type\":\"UTILITY_SUBSIDY\",\"amount\":5000}],\"status\":\""+status+"\",\"startDate\":\"2026-09-01\",\"endDate\":\"2027-09-01\",\"updatedAt\":\"2026-08-23T10:00:00-03:00\"}}",ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent.class);}
    private SocialBenefitStatus expectedCalculatedStatus(String status){return switch(status){case "APPROVED"->SocialBenefitStatus.ACTIVE;case "SUSPENDED"->SocialBenefitStatus.SUSPENDED;case "FINALIZED"->SocialBenefitStatus.EXPIRED;default->SocialBenefitStatus.CANCELLED;};}
}
