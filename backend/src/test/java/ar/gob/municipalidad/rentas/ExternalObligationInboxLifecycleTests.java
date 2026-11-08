package ar.gob.municipalidad.rentas;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExternalObligationInboxLifecycleTests {
    @Autowired ExternalObligationService externalObligations;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired TaxConceptRepository concepts;
    @Autowired ExternalObligationRepository obligations;
    @Autowired DebtRepository debts;
    @Autowired ProcessedEventRepository processed;
    @Autowired IntegrationEventLogRepository logs;
    @Autowired IntegrationInbox inbox;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("scrum-777-test", null), null,
            List.of(new SimpleGrantedAuthority("ROLE_RENTAS"))));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void eventIdAndBusinessKeyDuplicatesCompleteInboxOnceAndCreateOneDebt() {
        TaxpayerReference taxpayer=taxpayer("777-duplicate");
        concept();
        String externalReference="INF-"+UUID.randomUUID();
        UUID eventId=UUID.randomUUID();
        var original=event(eventId,externalReference,taxpayer.externalId);
        long processedBefore=processed.count();

        ExternalObligation obligation=externalObligations.consumeInfraction(original);
        ExternalObligation sameEvent=externalObligations.consumeInfraction(original);
        ExternalObligation businessDuplicate=externalObligations.consumeInfraction(
            event(UUID.randomUUID(),externalReference,taxpayer.externalId));

        assertThat(sameEvent.id).isEqualTo(obligation.id);
        assertThat(businessDuplicate.id).isEqualTo(obligation.id);
        assertThat(processed.count()).isEqualTo(processedBefore+2);
        assertThat(processed.existsByExternalEventId(eventId.toString())).isTrue();
        assertThat(obligations.findBySourceModuleAndExternalTypeAndExternalReferenceId(
            "M7",ExternalObligationType.TRAFFIC_INFRACTION,externalReference)).contains(obligation);
        assertThat(debts.findAll().stream().filter(d->obligation.id.equals(d.externalObligationId))).hasSize(1);
        assertThat(logs.findFirstByExternalEventIdOrderByIdDesc(eventId.toString()).orElseThrow().status)
            .isEqualTo(IntegrationEventStatus.PROCESSED);
    }

    @Test
    void manualRetryCompletesTheOriginalFailedInboxRecord() {
        concept();
        UUID eventId=UUID.randomUUID();
        String taxpayerExternalId="777-retry-"+eventId;
        var event=event(eventId,"INF-RETRY-"+eventId,taxpayerExternalId);
        long processedBefore=processed.count();

        ExternalObligation failed=externalObligations.consumeInfraction(event);
        IntegrationEventLog failedLog=logs.findFirstByExternalEventIdOrderByIdDesc(eventId.toString()).orElseThrow();
        assertThat(failed.status).isEqualTo(ExternalObligationStatus.ERROR);
        assertThat(failedLog.status).isEqualTo(IntegrationEventStatus.FAILED);
        assertThat(processed.existsByExternalEventId(eventId.toString())).isFalse();
        assertThat(debts.findAll()).noneMatch(d->failed.id.equals(d.externalObligationId));

        TaxpayerReference taxpayer=taxpayer(taxpayerExternalId);
        ExternalObligation retried=externalObligations.retry(failed.id);
        IntegrationEventLog completedLog=logs.findById(failedLog.id).orElseThrow();

        assertThat(retried.status).isEqualTo(ExternalObligationStatus.PROCESSED);
        assertThat(completedLog.status).isEqualTo(IntegrationEventStatus.PROCESSED);
        assertThat(completedLog.retryCount).isEqualTo(1);
        assertThat(completedLog.errorMessage).isNull();
        assertThat(processed.count()).isEqualTo(processedBefore+1);
        assertThat(processed.existsByExternalEventId(eventId.toString())).isTrue();
        assertThat(debts.findByTaxpayerId(taxpayer.id).stream().filter(d->retried.id.equals(d.externalObligationId))).hasSize(1);
    }

    @Test
    void retryThatStillCannotResolveTaxpayerKeepsTheSameFailedObligationAndInboxRecord() {
        concept();
        UUID eventId=UUID.randomUUID();
        var event=event(eventId,"INF-RETRY-FAILED-"+eventId,"777-missing-"+eventId);
        ExternalObligation obligation=externalObligations.consumeInfraction(event);
        IntegrationEventLog log=logs.findFirstByEventIdOrderByIdDesc(eventId).orElseThrow();

        ExternalObligation retried=externalObligations.retry(obligation.id);

        assertThat(retried.id).isEqualTo(obligation.id);
        assertThat(retried.status).isEqualTo(ExternalObligationStatus.ERROR);
        assertThat(retried.retryCount).isEqualTo(1);
        assertThat(logs.findAll()).filteredOn(entry->entry.externalEventId.equals(eventId.toString())).singleElement()
            .satisfies(entry->{
                assertThat(entry.id).isEqualTo(log.id);
                assertThat(entry.status).isEqualTo(IntegrationEventStatus.FAILED);
                assertThat(entry.retryCount).isEqualTo(1);
                assertThat(entry.errorMessage).isNotBlank();
            });
        assertThat(processed.existsByExternalEventId(eventId.toString())).isFalse();
        assertThat(debts.findAll()).noneMatch(debt->obligation.id.equals(debt.externalObligationId));
    }

    @Test
    void retryAfterSuccessIsRejectedWithoutChangingTheProcessedObligation() {
        TaxpayerReference taxpayer=taxpayer("777-already-processed");
        concept();
        UUID eventId=UUID.randomUUID();
        ExternalObligation obligation=externalObligations.consumeInfraction(
            event(eventId,"INF-ALREADY-PROCESSED-"+eventId,taxpayer.externalId));

        assertThat(obligation.status).isEqualTo(ExternalObligationStatus.PROCESSED);
        assertThatThrownBy(()->externalObligations.retry(obligation.id))
            .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.code).isEqualTo("EXTERNAL_OBLIGATION_NOT_RETRYABLE"));

        assertThat(obligations.findById(obligation.id).orElseThrow().retryCount).isZero();
        assertThat(processed.existsByExternalEventId(eventId.toString())).isTrue();
        assertThat(debts.findAll().stream().filter(debt->obligation.id.equals(debt.externalObligationId))).hasSize(1);
    }

    @Test
    void redeliveryWithNewEventIdForAnExistingFailedBusinessKeyKeepsOneObligation() {
        concept();
        String externalReference="INF-FAILED-DUPLICATE-"+UUID.randomUUID();
        String missingTaxpayer="777-missing-duplicate-"+UUID.randomUUID();
        UUID originalEventId=UUID.randomUUID(),duplicateEventId=UUID.randomUUID();
        ExternalObligation original=externalObligations.consumeInfraction(event(originalEventId,externalReference,missingTaxpayer));

        ExternalObligation duplicate=externalObligations.consumeInfraction(event(duplicateEventId,externalReference,missingTaxpayer));

        assertThat(duplicate.id).isEqualTo(original.id);
        assertThat(obligations.findBySourceModuleAndExternalTypeAndExternalReferenceId(
            "M7",ExternalObligationType.TRAFFIC_INFRACTION,externalReference)).contains(original);
        assertThat(obligations.findAll().stream().filter(item->"M7".equals(item.sourceModule)
            &&externalReference.equals(item.externalReferenceId))).hasSize(1);
        assertThat(logs.findAll()).filteredOn(log->originalEventId.toString().equals(log.externalEventId)
            ||duplicateEventId.toString().equals(log.externalEventId)).hasSize(2)
            .allSatisfy(log->assertThat(log.status).isEqualTo(IntegrationEventStatus.FAILED));
        assertThat(processed.existsByExternalEventId(originalEventId.toString())).isFalse();
        assertThat(processed.existsByExternalEventId(duplicateEventId.toString())).isFalse();
        assertThat(debts.findAll()).noneMatch(debt->original.id.equals(debt.externalObligationId));
    }

    @Test
    void retryOfM4ObligationWithoutAnInboxLogProcessesWithoutCreatingAnInboxRecord() {
        String cuit="30712345678";
        TaxConcept permitConcept=concept("PERMIT_FEE",TaxConceptType.FEE,"M4");
        String originId=UUID.randomUUID().toString();
        UUID eventId=UUID.randomUUID();
        var event=new CoreM4Events.Envelope<>(eventId,"permitFeeGenerated","1.0",OffsetDateTime.now(),"habilitaciones",
            UUID.randomUUID(),null,new CoreM4Events.PermitFeeGeneratedData(originId,"APP-777","EST-777",cuit,
                new BigDecimal("90.00"),"ARS"));

        ExternalObligation failed=externalObligations.createM4(event,ExternalObligationType.PERMIT_FEE,originId,cuit,
            new BigDecimal("90.00"),LocalDate.now().plusDays(10));
        assertThat(failed.status).isEqualTo(ExternalObligationStatus.ERROR);
        assertThat(logs.findAll()).isEmpty();

        organization(cuit);
        ExternalObligation retried=externalObligations.retry(failed.id);

        assertThat(retried.status).isEqualTo(ExternalObligationStatus.PROCESSED);
        assertThat(retried.retryCount).isEqualTo(1);
        assertThat(logs.findAll()).isEmpty();
        assertThat(processed.existsByExternalEventId(eventId.toString())).isFalse();
        assertThat(debts.findAll().stream().filter(debt->failed.id.equals(debt.externalObligationId))).hasSize(1);
    }

    @Test
    void retryCompletesAnAlreadyProcessedInboxEntryWithoutDuplicatingItsMarkerOrTimestamp() {
        concept();
        UUID eventId=UUID.randomUUID();
        String taxpayerExternalId="777-preprocessed-"+eventId;
        var event=event(eventId,"INF-PREPROCESSED-"+eventId,taxpayerExternalId);
        ExternalObligation failed=externalObligations.consumeInfraction(event);
        IntegrationEventLog log=logs.findFirstByEventIdOrderByIdDesc(eventId).orElseThrow();
        ProcessedEvent marker=new ProcessedEvent();
        marker.eventId=IntegrationInbox.technicalId(eventId.toString());
        marker.externalEventId=eventId.toString();
        marker.eventType=event.eventType();
        marker.sourceModule=event.sourceModule();
        marker.receivedAt=log.receivedAt;
        OffsetDateTime markerProcessedAt=OffsetDateTime.now().minusMinutes(2);
        marker.processedAt=markerProcessedAt;
        processed.save(marker);
        taxpayer(taxpayerExternalId);

        ExternalObligation retried=externalObligations.retry(failed.id);
        OffsetDateTime completedAt=logs.findById(log.id).orElseThrow().processedAt;
        IntegrationEventLog completedLog=logs.findById(log.id).orElseThrow();
        completedLog.processedAt=null;
        inbox.complete(completedLog);
        OffsetDateTime recoveredTimestamp=logs.findById(log.id).orElseThrow().processedAt;
        OffsetDateTime stableTimestamp=OffsetDateTime.parse("2026-11-06T12:00:00Z");
        completedLog.processedAt=recoveredTimestamp;
        inbox.complete(completedLog);
        completedLog.processedAt=stableTimestamp;
        inbox.complete(completedLog);

        assertThat(retried.status).isEqualTo(ExternalObligationStatus.PROCESSED);
        assertThat(completedAt).isNotNull();
        assertThat(recoveredTimestamp).isNotNull();
        assertThat(processed.findById(marker.eventId).orElseThrow().processedAt).isEqualTo(markerProcessedAt);
        assertThat(logs.findById(log.id).orElseThrow().processedAt).isEqualTo(stableTimestamp);
        assertThat(processed.count()).isEqualTo(1);
        assertThat(debts.findAll().stream().filter(debt->failed.id.equals(debt.externalObligationId))).hasSize(1);
    }

    private TaxpayerReference taxpayer(String externalId) {
        TaxpayerReference taxpayer=new TaxpayerReference();
        taxpayer.taxpayerType=TaxpayerType.CITIZEN;
        taxpayer.externalId=externalId;
        taxpayer.displayName="SCRUM-777 synthetic taxpayer";
        taxpayer.externalStatus=TaxpayerStatus.ACTIVE;
        taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();
        return taxpayers.save(taxpayer);
    }

    private TaxConcept concept() {
        return concept("TRAFFIC_INFRACTION",TaxConceptType.FINE,"M7");
    }

    private TaxConcept concept(String code,TaxConceptType type,String originModule) {
        return concepts.findByCode(code).orElseGet(()->{
            TaxConcept concept=new TaxConcept();
            concept.code=code;
            concept.name="SCRUM-777 synthetic "+code;
            concept.type=type;
            concept.originModule=originModule;
            concept.active=true;
            concept.createdAt=concept.updatedAt=OffsetDateTime.now();
            return concepts.save(concept);
        });
    }

    private TaxpayerReference organization(String cuit) {
        TaxpayerReference taxpayer=new TaxpayerReference();
        taxpayer.taxpayerType=TaxpayerType.ORGANIZATION;
        taxpayer.externalId="777-org-"+cuit;
        taxpayer.cuit=cuit;
        taxpayer.displayName="SCRUM-777 synthetic organization";
        taxpayer.externalStatus=TaxpayerStatus.ACTIVE;
        taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();
        return taxpayers.save(taxpayer);
    }

    private ApiDtos.EventEnvelope<ApiDtos.InfractionData> event(UUID eventId,String reference,String taxpayerId) {
        var data=new ApiDtos.InfractionData(reference,TaxpayerType.CITIZEN,taxpayerId,
            new BigDecimal("75.00"),LocalDate.now().plusDays(10));
        return new ApiDtos.EventEnvelope<>(eventId,"infractionConfirmed",OffsetDateTime.now(),"M7",data);
    }
}
