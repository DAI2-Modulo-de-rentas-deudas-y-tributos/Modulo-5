package ar.gob.municipalidad.rentas;

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
    @Autowired TaxpayerIntegrationService taxpayerIntegration;@Autowired TicketIntegrationService ticketIntegration;@Autowired TicketService ticketService;
    @Autowired SocialBenefitIntegrationService benefitIntegration;@Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;@Autowired TaxpayerRepository taxpayers;@Autowired DebtRepository debts;@Autowired OutboxRepository outbox;
    @Autowired IntegrationReprocessService reprocess;@Autowired PreliminaryM4Consumer m4;

    @BeforeEach void authenticate(){var roles=List.of(new SimpleGrantedAuthority("ROLE_RENTAS"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("agent-1",null),null,roles));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void m1SynchronizationIsIdempotentAndUpdatesReference(){UUID id=UUID.randomUUID();ApiDtos.EventEnvelope<ApiDtos.TaxpayerEventData> created=new ApiDtos.EventEnvelope<>(id,"taxpayerCreated",OffsetDateTime.now(),"M1",new ApiDtos.TaxpayerEventData(TaxpayerType.CITIZEN,"M1-100","123",null,"Nombre inicial",TaxpayerStatus.ACTIVE));TaxpayerReference first=taxpayerIntegration.consume(created);TaxpayerReference duplicate=taxpayerIntegration.consume(created);ApiDtos.EventEnvelope<ApiDtos.TaxpayerEventData> updated=new ApiDtos.EventEnvelope<>(UUID.randomUUID(),"taxpayerUpdated",OffsetDateTime.now(),"M1",new ApiDtos.TaxpayerEventData(TaxpayerType.CITIZEN,"M1-100","123",null,"Nombre actualizado",TaxpayerStatus.BLOCKED));taxpayerIntegration.consume(updated);assertThat(duplicate.id).isEqualTo(first.id);assertThat(taxpayers.findById(first.id).orElseThrow().displayName).isEqualTo("Nombre actualizado");assertThat(taxpayers.findById(first.id).orElseThrow().externalStatus).isEqualTo(TaxpayerStatus.BLOCKED);}

    @Test void preliminaryM4FeeIsBlockedWithoutInventingTaxpayer(){long obligationsBefore=externalObligationRepository.count();long debtsBefore=debts.count();var event=new PreliminaryM4Events.Envelope<>("M4","permitFeeGenerated",new PreliminaryM4Events.PermitFeeGeneratedData("PERMIT-1","PA-1","EST-1",new BigDecimal("80")));IntegrationEventLog log=m4.consumePermitFee(event);assertThat(log.status).isEqualTo(IntegrationEventStatus.FAILED);assertThat(log.errorMessage).startsWith(PreliminaryM4Consumer.TAXPAYER_BLOCKER);assertThat(externalObligationRepository.count()).isEqualTo(obligationsBefore);assertThat(debts.count()).isEqualTo(debtsBefore);}

    @Test void m2TicketActionsPublishStatusThroughOutbox(){TaxpayerReference taxpayer=taxpayer("M2-100");ApiDtos.EventEnvelope<ApiDtos.TicketEventData> event=new ApiDtos.EventEnvelope<>(UUID.randomUUID(),"ticketCreated",OffsetDateTime.now(),"M2",new ApiDtos.TicketEventData("TICKET-1",taxpayer.externalId,"RENTAS","Consulta",TicketPriority.HIGH,TicketCaseStatus.OPEN,OffsetDateTime.now()));TicketCase ticket=ticketIntegration.consume(event);ticketService.assign(ticket.id);ticketService.complete(ticket.id,"Resuelto");assertThat(ticket.status).isEqualTo(TicketCaseStatus.COMPLETED);assertThat(outbox.findAll()).anyMatch(x->x.eventType.equals("updateTicketStatus")&&x.targetModule.equals("M2"));}

    @Test void activeM8BenefitIsAppliedByLiquidationWithoutOwningM8Data(){TaxpayerReference taxpayer=taxpayer("M8-100");TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("BENEFIT-CONCEPT","Beneficio",null,TaxConceptType.FEE,"M5"));TaxConfiguration config=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(config.id);catalog.approve(config.id);ApiDtos.EventEnvelope<ApiDtos.SocialBenefitEventData> event=new ApiDtos.EventEnvelope<>(UUID.randomUUID(),"socialBenefitUpdated",OffsetDateTime.now(),"M8",new ApiDtos.SocialBenefitEventData("BEN-1",taxpayer.externalId,"SOCIAL",SocialBenefitStatus.ACTIVE,new BigDecimal("30"),LocalDate.now().minusDays(1),null,List.of(concept.code)));benefitIntegration.consume(event);ApiDtos.LiquidationPreview preview=liquidations.preview(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(10)));assertThat(preview.discountAmount()).isEqualByComparingTo("30.00");assertThat(preview.exemptionAmount()).isZero();assertThat(preview.components()).anyMatch(x->x.type()==LiquidationComponentType.SOCIAL_BENEFIT);assertThat(preview.finalAmount()).isEqualByComparingTo("70.00");}

    @Test void preliminaryM4ReprocessRemainsExplicitlyBlocked(){var event=new PreliminaryM4Events.Envelope<>("M4","commercialFineGenerated",new PreliminaryM4Events.CommercialFineGeneratedData("FINE-1",null,null,"EST-1","ACT-1",new BigDecimal("60"),"Incumplimiento",OffsetDateTime.now(),"MULTA-1"));IntegrationEventLog first=m4.consumeCommercialFine(event);IntegrationEventLog result=reprocess.reprocess(first.externalEventId,"Reintento técnico");assertThat(result.status).isEqualTo(IntegrationEventStatus.FAILED);assertThat(result.retryCount).isEqualTo(1);assertThat(result.errorMessage).startsWith(PreliminaryM4Consumer.TAXPAYER_BLOCKER);}

    @Test void failedOutboxEventRetriesAndPublishedEventIsNotSentAgain(){OutboxRepository repository=mock(OutboxRepository.class);OutboxEvent event=new OutboxEvent();event.id=UUID.randomUUID();event.eventType="paymentRegistered";event.targetModule="M1";event.aggregateType="Payment";event.aggregateId="1";event.payload="{}";event.status=OutboxStatus.PENDING;event.createdAt=OffsetDateTime.now();when(repository.findPublishable(any())).thenReturn(List.of(event),List.of(event),List.of());new OutboxPublisher(repository,(type,target,payload)->{throw new IllegalStateException("broker down");}).publishPending();assertThat(event.status).isEqualTo(OutboxStatus.FAILED);assertThat(event.retryCount).isEqualTo(1);assertThat(event.lastAttemptAt).isNotNull();assertThat(event.errorMessage).contains("broker down");int[] published={0};OutboxPublisher publisher=new OutboxPublisher(repository,(type,target,payload)->published[0]++);publisher.publishPending();publisher.publishPending();assertThat(event.status).isEqualTo(OutboxStatus.PUBLISHED);assertThat(event.publishedAt).isNotNull();assertThat(published[0]).isEqualTo(1);}

    private TaxpayerReference taxpayer(String external){return catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,external,"123",null,"Persona "+external));}
    @Autowired ExternalObligationRepository externalObligationRepository;
}
