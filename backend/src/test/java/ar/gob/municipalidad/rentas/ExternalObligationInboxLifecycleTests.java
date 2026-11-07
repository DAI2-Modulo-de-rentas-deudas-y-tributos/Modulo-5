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
        return concepts.findByCode("TRAFFIC_INFRACTION").orElseGet(()->{
            TaxConcept concept=new TaxConcept();
            concept.code="TRAFFIC_INFRACTION";
            concept.name="SCRUM-777 synthetic traffic infraction";
            concept.type=TaxConceptType.FINE;
            concept.originModule="M7";
            concept.active=true;
            concept.createdAt=concept.updatedAt=OffsetDateTime.now();
            return concepts.save(concept);
        });
    }

    private ApiDtos.EventEnvelope<ApiDtos.InfractionData> event(UUID eventId,String reference,String taxpayerId) {
        var data=new ApiDtos.InfractionData(reference,TaxpayerType.CITIZEN,taxpayerId,
            new BigDecimal("75.00"),LocalDate.now().plusDays(10));
        return new ApiDtos.EventEnvelope<>(eventId,"infractionConfirmed",OffsetDateTime.now(),"M7",data);
    }
}
