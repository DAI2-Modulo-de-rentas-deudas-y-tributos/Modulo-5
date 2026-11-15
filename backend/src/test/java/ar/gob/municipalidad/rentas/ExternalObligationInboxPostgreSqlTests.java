package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ExternalObligationInboxPostgreSqlTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/migration-postgresql,classpath:db/migration-demo");
    }

    @Autowired ExternalObligationService externalObligations;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired TaxConceptRepository concepts;
    @Autowired ExternalObligationRepository obligations;
    @Autowired DebtRepository debts;
    @Autowired ProcessedEventRepository processed;
    @Autowired IntegrationEventLogRepository logs;

    @Test
    void retryCompletesFailedInboxRecordWithoutDuplicateEconomicEffectOnPostgreSql() {
        concept();
        UUID eventId=UUID.randomUUID();
        String taxpayerExternalId="PG-777-"+eventId;
        var event=event(eventId,"INF-PG-777-"+eventId,taxpayerExternalId);
        long processedBefore=processed.count();
        long obligationsBefore=obligations.count();
        long debtsBefore=debts.count();

        ExternalObligation failed=externalObligations.consumeInfraction(event);
        IntegrationEventLog failedLog=logs.findFirstByExternalEventIdOrderByIdDesc(eventId.toString()).orElseThrow();
        assertThat(failed.status).isEqualTo(ExternalObligationStatus.ERROR);
        assertThat(failedLog.status).isEqualTo(IntegrationEventStatus.FAILED);
        assertThat(processed.existsByExternalEventId(eventId.toString())).isFalse();
        assertThat(obligations.count()).isEqualTo(obligationsBefore+1);
        assertThat(debts.count()).isEqualTo(debtsBefore);

        TaxpayerReference taxpayer=taxpayer(taxpayerExternalId);
        ExternalObligation retried=externalObligations.retry(failed.id);
        IntegrationEventLog completedLog=logs.findById(failedLog.id).orElseThrow();

        assertThat(retried.status).isEqualTo(ExternalObligationStatus.PROCESSED);
        assertThat(completedLog.status).isEqualTo(IntegrationEventStatus.PROCESSED);
        assertThat(completedLog.retryCount).isEqualTo(1);
        assertThat(completedLog.errorMessage).isNull();
        assertThat(processed.count()).isEqualTo(processedBefore+1);
        assertThat(processed.existsByExternalEventId(eventId.toString())).isTrue();
        assertThat(obligations.findBySourceModuleAndExternalTypeAndExternalReferenceId(
            "M7",ExternalObligationType.TRAFFIC_INFRACTION,event.data().externalReferenceId())).hasValueSatisfying(stored->
                assertThat(stored.id).isEqualTo(retried.id));
        assertThat(debts.findByTaxpayerId(taxpayer.id).stream().filter(d->retried.id.equals(d.externalObligationId))).hasSize(1);

        assertThat(externalObligations.consumeInfraction(event).id).isEqualTo(retried.id);
        assertThat(processed.count()).isEqualTo(processedBefore+1);
        assertThat(debts.findByTaxpayerId(taxpayer.id).stream().filter(d->retried.id.equals(d.externalObligationId))).hasSize(1);
    }

    private TaxpayerReference taxpayer(String externalId) {
        TaxpayerReference taxpayer=new TaxpayerReference();
        taxpayer.taxpayerType=TaxpayerType.CITIZEN;
        taxpayer.externalId=externalId;
        taxpayer.displayName="SCRUM-777 synthetic PostgreSQL taxpayer";
        taxpayer.externalStatus=TaxpayerStatus.ACTIVE;
        taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();
        return taxpayers.save(taxpayer);
    }

    private TaxConcept concept() {
        return concepts.findByCode("TRAFFIC_INFRACTION").orElseGet(()->{
            TaxConcept concept=new TaxConcept();
            concept.code="TRAFFIC_INFRACTION";
            concept.name="SCRUM-777 PostgreSQL traffic infraction";
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
