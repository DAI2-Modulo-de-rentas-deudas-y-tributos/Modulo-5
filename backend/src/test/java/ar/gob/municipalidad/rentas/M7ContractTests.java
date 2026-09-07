package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test") @SpringBootTest @Transactional
class M7ContractTests {
    private static final String DOCUMENTED_EVENT="""
        {
          "eventId": "7f9c91f0-03c6-4baf-8791-8cb2c38981d2",
          "eventType": "infractionConfirmed",
          "occurredAt": "2026-08-24T14:30:00-03:00",
          "sourceModule": "transito",
          "data": {
            "infractionId": "93fd4382-2343-4f3f-a127-9328b29fc520",
            "debtorId": "40123456",
            "debtorIdType": "DNI",
            "licensePlate": "AB123CD",
            "infractionType": "ILLEGAL_PARKING",
            "infractionDateTime": "2026-08-24T13:45:00-03:00",
            "baseAmount": 50000,
            "aggravatingFactors": ["REINCIDENCE"],
            "finalAmount": 75000,
            "inspectorId": "INS-245",
            "location": {
              "street": "Av. Cabildo",
              "crossStreet": "Juramento",
              "latitude": -34.5621,
              "longitude": -58.4563
            }
          }
        }
        """;

    @Autowired ObjectMapper json;@Autowired ConfirmedM7Consumer m7;@Autowired TaxpayerRepository taxpayers;
    @Autowired TaxConceptRepository concepts;@Autowired ExternalObligationRepository obligations;@Autowired DebtRepository debts;
    @Autowired ProcessedEventRepository processed;@Autowired IntegrationEventLogRepository logs;@Autowired IntegrationReprocessService reprocess;
    @Autowired PlatformTransactionManager transactionManager;

    @Test void documentedPayloadUsesFinalAmountAndCreatesOneEconomicEffect() throws Exception {
        long before=obligations.count();TaxpayerReference taxpayer=citizen("40123456");concept();var event=read(DOCUMENTED_EVENT);
        ExternalObligation first=m7.consume(event);ExternalObligation sameEvent=m7.consume(event);
        var functionalDuplicate=new ConfirmedInboundEvents.M7InfractionConfirmedEvent(UUID.randomUUID(),event.eventType(),event.occurredAt(),event.sourceModule(),event.data());
        ExternalObligation sameInfraction=m7.consume(functionalDuplicate);

        assertThat(first.id).isEqualTo(sameEvent.id).isEqualTo(sameInfraction.id);
        assertThat(first.sourceModule).isEqualTo("M7");assertThat(first.externalReferenceId).isEqualTo("93fd4382-2343-4f3f-a127-9328b29fc520");
        assertThat(first.externalTaxpayerType).isEqualTo(TaxpayerType.CITIZEN);assertThat(first.externalTaxpayerId).isEqualTo("40123456");
        assertThat(first.amount).isEqualByComparingTo("75000.00");assertThat(first.dueDate).isEqualTo(event.data().infractionDateTime().toLocalDate());
        assertThat(event.data().location().latitude()).isEqualByComparingTo("-34.5621");assertThat(event.data().location().longitude()).isEqualByComparingTo("-58.4563");
        assertThat(debts.findByTaxpayerId(taxpayer.id)).singleElement().satisfies(debt->{assertThat(debt.originalAmount).isEqualByComparingTo("75000.00");assertThat(debt.externalObligationId).isEqualTo(first.id);});
        assertThat(obligations.count()).isEqualTo(before+1);assertThat(processed.existsByExternalEventId(event.eventId().toString())).isTrue();
        IntegrationEventLog log=logs.findFirstByExternalEventIdOrderByIdDesc(event.eventId().toString()).orElseThrow();
        assertThat(log.sourceModule).isEqualTo("M7");assertThat(log.payload).contains("\"sourceModule\":\"transito\"","\"baseAmount\":50000","\"aggravatingFactors\"");
    }

    @Test void onlyExactDocumentedSourceIsAcceptedWithoutPartialWrites() throws Exception {
        long obligationsBefore=obligations.count(),debtsBefore=debts.count(),logsBefore=logs.count();citizen("40123456");concept();var event=read(DOCUMENTED_EVENT.replace("\"transito\"","\"M7\""));
        assertThatThrownBy(()->m7.consume(event)).isInstanceOf(BusinessException.class).hasMessageContaining("sourceModule transito");
        assertThat(obligations.count()).isEqualTo(obligationsBefore);assertThat(debts.count()).isEqualTo(debtsBefore);assertThat(logs.count()).isEqualTo(logsBefore);
    }

    @Test void invalidPayloadDoesNotCreatePartialDebt() throws Exception {
        long obligationsBefore=obligations.count(),debtsBefore=debts.count(),logsBefore=logs.count();citizen("40123456");concept();var event=read(DOCUMENTED_EVENT.replace("\"licensePlate\": \"AB123CD\"","\"licensePlate\": \"\""));
        assertThatThrownBy(()->m7.consume(event)).isInstanceOf(BusinessException.class).hasMessageContaining("licensePlate");
        assertThat(obligations.count()).isEqualTo(obligationsBefore);assertThat(debts.count()).isEqualTo(debtsBefore);assertThat(logs.count()).isEqualTo(logsBefore);
    }

    @Test void cuitResolvesOrganizationExplicitly() throws Exception {
        TaxpayerReference organization=organization("30712345678");concept();var event=read(DOCUMENTED_EVENT
            .replace("40123456","30712345678").replace("\"DNI\"","\"CUIT\"").replace("93fd4382-2343-4f3f-a127-9328b29fc520",UUID.randomUUID().toString()).replace("7f9c91f0-03c6-4baf-8791-8cb2c38981d2",UUID.randomUUID().toString()));
        ExternalObligation result=m7.consume(event);assertThat(result.taxpayerId).isEqualTo(organization.id);assertThat(result.externalTaxpayerType).isEqualTo(TaxpayerType.ORGANIZATION);
    }

    @Test void missingTaxpayerIsTraceableAndRetryDoesNotDuplicate() throws Exception {
        long obligationsBefore=obligations.count(),debtsBefore=debts.count();concept();var event=read(DOCUMENTED_EVENT);ExternalObligation failed=m7.consume(event);
        assertThat(failed.status).isEqualTo(ExternalObligationStatus.ERROR);assertThat(debts.count()).isEqualTo(debtsBefore);assertThat(processed.existsByExternalEventId(event.eventId().toString())).isFalse();
        assertThat(logs.findFirstByExternalEventIdOrderByIdDesc(event.eventId().toString()).orElseThrow().status).isEqualTo(IntegrationEventStatus.FAILED);
        TaxpayerReference taxpayer=citizen("40123456");IntegrationEventLog retried=reprocess.reprocess(event.eventId().toString(),"referencia sincronizada");
        assertThat(retried.status).isEqualTo(IntegrationEventStatus.PROCESSED);assertThat(obligations.count()).isEqualTo(obligationsBefore+1);assertThat(debts.findByTaxpayerId(taxpayer.id)).hasSize(1);
    }

    @Test void crashRollsBackWholeEffectAndRetryCreatesItOnce() throws Exception {
        String debtor="4099"+System.nanoTime();String eventId=UUID.randomUUID().toString();String infractionId=UUID.randomUUID().toString();
        TransactionTemplate setup=new TransactionTemplate(transactionManager);setup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        setup.executeWithoutResult(status->{citizen(debtor);concept();});
        var event=read(DOCUMENTED_EVENT.replace("40123456",debtor).replace("7f9c91f0-03c6-4baf-8791-8cb2c38981d2",eventId).replace("93fd4382-2343-4f3f-a127-9328b29fc520",infractionId));
        TransactionTemplate crashing=new TransactionTemplate(transactionManager);crashing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatThrownBy(()->crashing.executeWithoutResult(status->{m7.consume(event);throw new IllegalStateException("simulated crash");})).isInstanceOf(IllegalStateException.class);
        assertThat(obligations.findBySourceModuleAndExternalTypeAndExternalReferenceId("M7",ExternalObligationType.TRAFFIC_INFRACTION,infractionId)).isEmpty();
        ExternalObligation retried=m7.consume(event);
        assertThat(retried.status).isEqualTo(ExternalObligationStatus.PROCESSED);assertThat(debts.findByTaxpayerId(retried.taxpayerId)).hasSize(1);
    }

    private ConfirmedInboundEvents.M7InfractionConfirmedEvent read(String value)throws Exception{return json.readValue(value,ConfirmedInboundEvents.M7InfractionConfirmedEvent.class);}
    private TaxpayerReference citizen(String dni){TaxpayerReference t=new TaxpayerReference();t.taxpayerType=TaxpayerType.CITIZEN;t.externalId="M1-CIT-"+dni;t.dni=dni;t.displayName="Ciudadano M7";t.externalStatus=TaxpayerStatus.ACTIVE;t.createdAt=t.updatedAt=OffsetDateTime.now();return taxpayers.save(t);}
    private TaxpayerReference organization(String cuit){TaxpayerReference t=new TaxpayerReference();t.taxpayerType=TaxpayerType.ORGANIZATION;t.externalId="M1-ORG-"+cuit;t.cuit=cuit;t.displayName="Organización M7";t.externalStatus=TaxpayerStatus.ACTIVE;t.createdAt=t.updatedAt=OffsetDateTime.now();return taxpayers.save(t);}
    private TaxConcept concept(){return concepts.findByCode("TRAFFIC_INFRACTION").orElseGet(()->{TaxConcept c=new TaxConcept();c.code="TRAFFIC_INFRACTION";c.name="Infracción de tránsito";c.type=TaxConceptType.FINE;c.originModule="M7";c.active=true;c.createdAt=c.updatedAt=OffsetDateTime.now();return concepts.save(c);});}
}
