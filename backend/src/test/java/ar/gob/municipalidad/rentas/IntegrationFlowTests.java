package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test") @SpringBootTest @Transactional
class IntegrationFlowTests {
    @Autowired ConfirmedM1Consumer taxpayerIntegration;@Autowired TicketIntegrationService ticketIntegration;@Autowired TicketService ticketService;
    @Autowired ConfirmedM8Consumer benefitIntegration;@Autowired SocialBenefitRuleService socialRules;@Autowired ObjectMapper json;@Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;@Autowired TaxpayerRepository taxpayers;@Autowired DebtRepository debts;@Autowired OutboxRepository outbox;
    @Autowired IntegrationReprocessService reprocess;@Autowired PreliminaryM4Consumer m4;

    @BeforeEach void authenticate(){var roles=List.of(new SimpleGrantedAuthority("ROLE_RENTAS"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("agent-1",null),null,roles));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void confirmedM1CitizenRegistrationIsIdempotentAndBlockedUpdateKeepsCanonicalReference(){UUID canonicalId=UUID.randomUUID();var created=m1Citizen(canonicalId,"REGISTERED",json.valueToTree(new ConfirmedInboundEvents.M1CitizenRegisteredDetails("40100100","20401001009","Nombre","Inicial",LocalDate.of(1990,1,1),"ACTIVE",null)));TaxpayerReference first=taxpayerIntegration.consumeCitizen(created);TaxpayerReference duplicate=taxpayerIntegration.consumeCitizen(created);var blocked=m1Citizen(canonicalId,"BLOCKED",json.valueToTree(new ConfirmedInboundEvents.M1CitizenBlockedDetails("DOCUMENTATION_REVIEW",true)));TaxpayerReference updated=taxpayerIntegration.consumeCitizen(blocked);assertThat(duplicate.id).isEqualTo(first.id);assertThat(updated.id).isEqualTo(first.id);assertThat(taxpayers.findById(first.id).orElseThrow().displayName).isEqualTo("Nombre Inicial");assertThat(taxpayers.findById(first.id).orElseThrow().canonicalCitizenId).isEqualTo(canonicalId);assertThat(taxpayers.findById(first.id).orElseThrow().externalStatus).isEqualTo(TaxpayerStatus.BLOCKED);}

    @Test void preliminaryM4FeeIsBlockedWithoutInventingTaxpayer(){long obligationsBefore=externalObligationRepository.count();long debtsBefore=debts.count();var event=new PreliminaryM4Events.Envelope<>("M4","permitFeeGenerated",new PreliminaryM4Events.PermitFeeGeneratedData("PERMIT-1","PA-1","EST-1",new BigDecimal("80")));IntegrationEventLog log=m4.consumePermitFee(event);assertThat(log.status).isEqualTo(IntegrationEventStatus.FAILED);assertThat(log.errorMessage).startsWith(PreliminaryM4Consumer.TAXPAYER_BLOCKER);assertThat(externalObligationRepository.count()).isEqualTo(obligationsBefore);assertThat(debts.count()).isEqualTo(debtsBefore);}

    @Test void legacyNumericTicketCanBeManagedLocallyWithoutPublishingInvalidM2Event(){TaxpayerReference taxpayer=taxpayer("100");ApiDtos.EventEnvelope<ApiDtos.TicketEventData> event=new ApiDtos.EventEnvelope<>(UUID.randomUUID(),"ticketCreated",OffsetDateTime.now(),"M2",new ApiDtos.TicketEventData(1001L,100L,"RENTAS","Consulta",TicketPriority.HIGH,null));TicketCase ticket=ticketIntegration.consume(event);ticketService.assign(ticket.id);ticketService.complete(ticket.id,"Resuelto");assertThat(ticket.status).isEqualTo(TicketCaseStatus.COMPLETED);assertThat(outbox.findAll()).noneMatch(x->x.eventType.equals("updateTicketStatus"));}

    @Test void confirmedM8BenefitIsAppliedByLiquidationForItsCanonicalCitizen(){UUID canonicalId=UUID.randomUUID(),programId=UUID.randomUUID();TaxpayerReference taxpayer=ConfirmedIntegrationTestFixtures.registerCitizen(taxpayerIntegration,json,canonicalId,"40100101","20401001017","Ciudadano","Confirmado");TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("BENEFIT-CONCEPT","Beneficio",null,TaxConceptType.FEE,"M5"));TaxConfiguration config=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(config.id);TestActorSupport.asApprover(() -> catalog.approve(config.id));LocalDate validFrom=YearMonth.now().atDay(1);ConfirmedIntegrationTestFixtures.approveRule(socialRules,programId,concept.code,new BigDecimal("30"),validFrom,null);SocialBenefitReference benefit=ConfirmedIntegrationTestFixtures.approvedBenefit(benefitIntegration,canonicalId,programId,validFrom,null);assertThat(benefit.taxpayerId).isEqualTo(taxpayer.id);ApiDtos.LiquidationPreview preview=liquidations.preview(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(10)));assertThat(preview.discountAmount()).isEqualByComparingTo("30.00");assertThat(preview.exemptionAmount()).isZero();assertThat(preview.components()).anyMatch(x->x.type()==LiquidationComponentType.SOCIAL_BENEFIT);assertThat(preview.finalAmount()).isEqualByComparingTo("70.00");}

    @Test void preliminaryM4ReprocessRemainsExplicitlyBlocked(){var event=new PreliminaryM4Events.Envelope<>("M4","commercialFineGenerated",new PreliminaryM4Events.CommercialFineGeneratedData("FINE-1",null,null,"EST-1","ACT-1",new BigDecimal("60"),"Incumplimiento",OffsetDateTime.now(),"MULTA-1"));IntegrationEventLog first=m4.consumeCommercialFine(event);IntegrationEventLog result=reprocess.reprocess(first.externalEventId,"Reintento técnico");assertThat(result.status).isEqualTo(IntegrationEventStatus.FAILED);assertThat(result.retryCount).isEqualTo(1);assertThat(result.errorMessage).startsWith(PreliminaryM4Consumer.TAXPAYER_BLOCKER);}

    @Test void failedOutboxEventRetriesAndBrokerConfirmDoesNotClaimConsumerDelivery(){OutboxRepository repository=mock(OutboxRepository.class);OutboxEvent event=new OutboxEvent();event.id=UUID.randomUUID();event.eventType="paymentRegistered";event.targetModule="M1";event.aggregateType="Payment";event.aggregateId="1";event.payload="{}";event.status=OutboxStatus.PENDING;event.createdAt=OffsetDateTime.now();when(repository.findPublishable(any())).thenReturn(List.of(event),List.of(event),List.of());new OutboxPublisher(repository,(type,target,payload)->{throw new IllegalStateException("broker down with secret");}).publishPending();assertThat(event.status).isEqualTo(OutboxStatus.FAILED);assertThat(event.retryCount).isEqualTo(1);assertThat(event.lastAttemptAt).isNotNull();assertThat(event.errorMessage).isEqualTo("CORE_BROKER_PUBLISH_FAILED");int[] published={0};OutboxPublisher publisher=new OutboxPublisher(repository,(type,target,payload)->{published[0]++;return OutboxStatus.BROKER_CONFIRMED;});publisher.publishPending();publisher.publishPending();assertThat(event.status).isEqualTo(OutboxStatus.BROKER_CONFIRMED);assertThat(event.publishedAt).isNotNull();assertThat(published[0]).isEqualTo(1);}

    @Test void rabbitModeWaitsForBrokerConfirmedPrerequisite(){OutboxRepository repository=mock(OutboxRepository.class);OutboxEvent followup=new OutboxEvent();followup.eventType="updatePaymentPlanStatus";followup.targetModule="CORE";followup.aggregateType="PaymentPlanRequest";followup.aggregateId="request-uuid";followup.payload="{\"data\":{\"requestId\":\""+UUID.randomUUID()+"\",\"citizenId\":\""+UUID.randomUUID()+"\",\"status\":\"REJECTED\",\"reason\":\"No elegible\"}}";followup.status=OutboxStatus.PENDING;when(repository.findPublishable(any())).thenReturn(List.of(followup));when(repository.existsByEventTypeAndAggregateTypeAndAggregateIdAndStatusIn("paymentPlanRequested","PaymentPlanRequest","request-uuid",List.of(OutboxStatus.BROKER_CONFIRMED))).thenReturn(false);int[] published={0};new OutboxPublisher(repository,(type,target,payload)->{published[0]++;return OutboxStatus.BROKER_CONFIRMED;},"rabbitmq").publishPending();assertThat(published[0]).isZero();assertThat(followup.status).isEqualTo(OutboxStatus.PENDING);}

    private TaxpayerReference taxpayer(String external){return catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,external,"123",null,"Persona "+external));}
    private ConfirmedInboundEvents.M1CoreEnvelope<ConfirmedInboundEvents.M1CitizenUpdatedData> m1Citizen(UUID citizenId,String updateType,com.fasterxml.jackson.databind.JsonNode details){return new ConfirmedInboundEvents.M1CoreEnvelope<>(UUID.randomUUID(),"citizenUpdated","1.0",OffsetDateTime.now(),"ciudadanos",UUID.randomUUID(),null,new ConfirmedInboundEvents.M1CitizenUpdatedData(citizenId,updateType,details,new ConfirmedInboundEvents.UpdatedBy("SYSTEM","test")));}
    @Autowired ExternalObligationRepository externalObligationRepository;
}
