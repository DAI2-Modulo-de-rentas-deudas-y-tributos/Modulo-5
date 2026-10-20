package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test") @SpringBootTest @Transactional
class CoreM4ContractTests {
    private static final String FEE="""
        {"eventId":"67ea68d5-f946-4016-b563-abc6e81d29c1","eventType":"permitFeeGenerated",
         "eventVersion":"1.0","occurredAt":"2026-10-17T09:00:00-03:00","sourceModule":"habilitaciones",
         "correlationId":"5d59204d-f2ac-433a-ab76-75a40178a3a9","causationId":null,
         "data":{"id":"FEE-001","permitApplicationId":"PA-001","establishmentId":"EST-001",
                 "cuit":"30712345678","amount":50000,"currency":"ARS"}}
        """;
    private static final String FINE="""
        {"eventId":"6727f2d7-a5ea-48a5-9b37-63e12fa0df94","eventType":"commercialFineGenerated",
         "eventVersion":"1.0","occurredAt":"2026-10-17T09:11:00-03:00","sourceModule":"habilitaciones",
         "data":{"id":"FINE-001","establishmentId":"EST-001","actId":"ACT-001",
                 "cuit":"30712345678","amount":100000,"currency":"ARS","reason":"Incumplimiento comercial",
                 "decidedAt":"2026-10-17T09:10:00-03:00","externalRef":"MULTA-2026-00123",
                 "sourceViolationId":"V-001","sourceModule":"M6"}}
        """;

    @Autowired ObjectMapper json;@Autowired CoreM4Consumer consumer;@Autowired CatalogService catalog;
    @Autowired IntegrationEventLogRepository logs;@Autowired ExternalObligationRepository obligations;
    @Autowired DebtRepository debts;

    @Test void acceptsCoreEnvelopeAndKeepsUnresolvedFeeWithoutCreatingDebt()throws Exception{
        var event=json.readValue(FEE,new TypeReference<CoreM4Events.Envelope<CoreM4Events.PermitFeeGeneratedData>>(){});
        long beforeLogs=logs.count(),beforeObligations=obligations.count(),beforeDebts=debts.count();
        IntegrationEventLog first=consumer.consumePermitFee(event),duplicate=consumer.consumePermitFee(event);
        assertThat(duplicate.id).isEqualTo(first.id);
        assertThat(first.externalEventId).isEqualTo(event.eventId().toString());
        assertThat(first.businessKey).isEqualTo("habilitaciones:permitFeeGenerated:FEE-001");
        assertThat(first.status).isEqualTo(IntegrationEventStatus.FAILED);
        assertThat(first.errorMessage).startsWith(CoreM4Consumer.TAXPAYER_PENDING);
        assertThat(logs.count()).isEqualTo(beforeLogs+1);
        assertThat(obligations.count()).isEqualTo(beforeObligations);
        assertThat(debts.count()).isEqualTo(beforeDebts);
    }

    @Test void finePreservesTheInnerM6SourceSeparatelyFromEnvelope()throws Exception{
        var event=json.readValue(FINE,new TypeReference<CoreM4Events.Envelope<CoreM4Events.CommercialFineGeneratedData>>(){});
        IntegrationEventLog log=consumer.consumeCommercialFine(event);
        assertThat(log.sourceModule).isEqualTo("habilitaciones");
        assertThat(log.payload).contains("\"sourceModule\":\"M6\"");
        assertThat(log.businessKey).isEqualTo("habilitaciones:commercialFineGenerated:FINE-001");
    }

    @Test void sameBusinessOriginWithAnotherEventIdIsIgnored()throws Exception{
        var event=json.readValue(FEE,new TypeReference<CoreM4Events.Envelope<CoreM4Events.PermitFeeGeneratedData>>(){});
        consumer.consumePermitFee(event);
        var duplicate=new CoreM4Events.Envelope<>(UUID.randomUUID(),event.eventType(),event.eventVersion(),
            event.occurredAt(),event.sourceModule(),event.correlationId(),event.causationId(),event.data());
        IntegrationEventLog ignored=consumer.consumePermitFee(duplicate);
        assertThat(ignored.status).isEqualTo(IntegrationEventStatus.IGNORED);
        assertThat(ignored.externalEventId).isEqualTo(duplicate.eventId().toString());
    }

    @Test void retryRecognizesCuitOnceTheLocalM1ProjectionExists()throws Exception{
        var event=json.readValue(FEE,new TypeReference<CoreM4Events.Envelope<CoreM4Events.PermitFeeGeneratedData>>(){});
        long debtsBefore=debts.count();
        IntegrationEventLog pending=consumer.consumePermitFee(event);
        catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.ORGANIZATION,"org-001",null,"30712345678","Organización de prueba"));
        IntegrationEventLog retried=consumer.consumePermitFee(event);
        assertThat(retried.id).isEqualTo(pending.id);
        assertThat(retried.errorMessage).startsWith(CoreM4Consumer.DUE_DATE_PENDING);
        assertThat(debts.count()).isEqualTo(debtsBefore);
    }
}
