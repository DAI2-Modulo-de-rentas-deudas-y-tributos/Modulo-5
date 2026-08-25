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
    @Autowired SocialBenefitIntegrationService benefitIntegration;@Autowired ExternalObligationService obligations;@Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;@Autowired TaxpayerRepository taxpayers;@Autowired DebtRepository debts;@Autowired OutboxRepository outbox;
    @Autowired IntegrationReprocessService reprocess;

    @BeforeEach void authenticate(){var roles=List.of(new SimpleGrantedAuthority("ROLE_RENTAS"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("agent-1",null),null,roles));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void m1SynchronizationIsIdempotentAndUpdatesReference(){UUID id=UUID.randomUUID();ApiDtos.EventEnvelope<ApiDtos.TaxpayerEventData> created=new ApiDtos.EventEnvelope<>(id,"taxpayerCreated",OffsetDateTime.now(),"M1",new ApiDtos.TaxpayerEventData(TaxpayerType.CITIZEN,"M1-100","123",null,"Nombre inicial",TaxpayerStatus.ACTIVE));TaxpayerReference first=taxpayerIntegration.consume(created);TaxpayerReference duplicate=taxpayerIntegration.consume(created);ApiDtos.EventEnvelope<ApiDtos.TaxpayerEventData> updated=new ApiDtos.EventEnvelope<>(UUID.randomUUID(),"taxpayerUpdated",OffsetDateTime.now(),"M1",new ApiDtos.TaxpayerEventData(TaxpayerType.CITIZEN,"M1-100","123",null,"Nombre actualizado",TaxpayerStatus.BLOCKED));taxpayerIntegration.consume(updated);assertThat(duplicate.id).isEqualTo(first.id);assertThat(taxpayers.findById(first.id).orElseThrow().displayName).isEqualTo("Nombre actualizado");assertThat(taxpayers.findById(first.id).orElseThrow().externalStatus).isEqualTo(TaxpayerStatus.BLOCKED);}

    @Test void failedM4ObligationCanBeRetriedAfterReferenceIsAvailable(){TaxpayerReference taxpayer=taxpayer("M4-100");ApiDtos.EventEnvelope<ApiDtos.ExternalObligationData> event=new ApiDtos.EventEnvelope<>(UUID.randomUUID(),"permitFeeGenerated",OffsetDateTime.now(),"M4",new ApiDtos.ExternalObligationData("PERMIT-1",TaxpayerType.CITIZEN,taxpayer.externalId,new BigDecimal("80"),LocalDate.now().plusDays(10)));ExternalObligation obligation=obligations.consumePermitFee(event);assertThat(obligation.status).isEqualTo(ExternalObligationStatus.ERROR);catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("PERMIT_FEE","Tasa de permiso",null,TaxConceptType.FEE,"M4"));obligations.retry(obligation.id);assertThat(obligation.status).isEqualTo(ExternalObligationStatus.PROCESSED);assertThat(debts.findByTaxpayerId(taxpayer.id)).singleElement().extracting(x->x.outstandingBalance).isEqualTo(new BigDecimal("80.00"));}

    @Test void m2TicketActionsPublishStatusThroughOutbox(){TaxpayerReference taxpayer=taxpayer("M2-100");ApiDtos.EventEnvelope<ApiDtos.TicketEventData> event=new ApiDtos.EventEnvelope<>(UUID.randomUUID(),"ticketCreated",OffsetDateTime.now(),"M2",new ApiDtos.TicketEventData("TICKET-1",taxpayer.externalId,"RENTAS","Consulta",TicketPriority.HIGH,TicketCaseStatus.OPEN,OffsetDateTime.now()));TicketCase ticket=ticketIntegration.consume(event);ticketService.assign(ticket.id);ticketService.complete(ticket.id,"Resuelto");assertThat(ticket.status).isEqualTo(TicketCaseStatus.COMPLETED);assertThat(outbox.findAll()).anyMatch(x->x.eventType.equals("updateTicketStatus")&&x.targetModule.equals("M2"));}

    @Test void activeM8BenefitIsAppliedByLiquidationWithoutOwningM8Data(){TaxpayerReference taxpayer=taxpayer("M8-100");TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("BENEFIT-CONCEPT","Beneficio",null,TaxConceptType.FEE,"M5"));TaxConfiguration config=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(config.id);catalog.approve(config.id);ApiDtos.EventEnvelope<ApiDtos.SocialBenefitEventData> event=new ApiDtos.EventEnvelope<>(UUID.randomUUID(),"socialBenefitUpdated",OffsetDateTime.now(),"M8",new ApiDtos.SocialBenefitEventData("BEN-1",taxpayer.externalId,"SOCIAL",SocialBenefitStatus.ACTIVE,new BigDecimal("30"),LocalDate.now().minusDays(1),null,List.of(concept.code)));benefitIntegration.consume(event);ApiDtos.LiquidationPreview preview=liquidations.preview(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(10)));assertThat(preview.discountAmount()).isEqualByComparingTo("30.00");assertThat(preview.exemptionAmount()).isZero();assertThat(preview.components()).anyMatch(x->x.type()==LiquidationComponentType.SOCIAL_BENEFIT);assertThat(preview.finalAmount()).isEqualByComparingTo("70.00");}

    @Test void failedIntegrationLogCanReprocessOriginalBrokerMessage(){TaxpayerReference taxpayer=taxpayer("M4-200");UUID eventId=UUID.randomUUID();ApiDtos.EventEnvelope<ApiDtos.ExternalObligationData> event=new ApiDtos.EventEnvelope<>(eventId,"commercialFineGenerated",OffsetDateTime.now(),"M4",new ApiDtos.ExternalObligationData("FINE-1",TaxpayerType.CITIZEN,taxpayer.externalId,new BigDecimal("60"),LocalDate.now().plusDays(10)));assertThat(obligations.consumeCommercialFine(event).status).isEqualTo(ExternalObligationStatus.ERROR);catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("COMMERCIAL_FINE","Multa comercial",null,TaxConceptType.FINE,"M4"));IntegrationEventLog result=reprocess.reprocess(eventId,"Referencia creada");assertThat(result.status).isEqualTo(IntegrationEventStatus.PROCESSED);assertThat(debts.findByTaxpayerId(taxpayer.id)).hasSize(1);}

    @Test void failedOutboxEventRetriesAndPublishedEventIsNotSentAgain(){OutboxRepository repository=mock(OutboxRepository.class);OutboxEvent event=new OutboxEvent();event.id=UUID.randomUUID();event.eventType="paymentRegistered";event.targetModule="M1";event.aggregateType="Payment";event.aggregateId="1";event.payload="{}";event.status=OutboxStatus.PENDING;event.createdAt=OffsetDateTime.now();when(repository.findPublishable(any())).thenReturn(List.of(event),List.of(event),List.of());new OutboxPublisher(repository,(type,target,payload)->{throw new IllegalStateException("broker down");}).publishPending();assertThat(event.status).isEqualTo(OutboxStatus.FAILED);assertThat(event.retryCount).isEqualTo(1);assertThat(event.lastAttemptAt).isNotNull();assertThat(event.errorMessage).contains("broker down");int[] published={0};OutboxPublisher publisher=new OutboxPublisher(repository,(type,target,payload)->published[0]++);publisher.publishPending();publisher.publishPending();assertThat(event.status).isEqualTo(OutboxStatus.PUBLISHED);assertThat(event.publishedAt).isNotNull();assertThat(published[0]).isEqualTo(1);}

    private TaxpayerReference taxpayer(String external){return catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,external,"123",null,"Persona "+external));}
}
