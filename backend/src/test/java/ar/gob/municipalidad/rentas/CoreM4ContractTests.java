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
         "correlationId":"5d59204d-f2ac-433a-ab76-75a40178a3a9",
         "data":{"id":"2ada0a54-7083-4717-8acc-1b88958795c7","permitApplicationId":"20bca69c-bf71-4119-9ab3-84207b5b952e","establishmentId":"600e32f8-db90-472d-8c1f-f1b4ee117dd6",
                 "cuit":"30712345678","amount":50000,"currency":"ARS"}}
        """;
    private static final String FINE="""
        {"eventId":"6727f2d7-a5ea-48a5-9b37-63e12fa0df94","eventType":"commercialFineGenerated",
         "eventVersion":"1.0","occurredAt":"2026-10-17T09:11:00-03:00","sourceModule":"habilitaciones",
         "correlationId":"da794e9c-b40f-48c1-bc98-b408cc6d90fb",
         "data":{"id":"3248f0c6-c43e-438d-9e83-f0c0acf9d510","establishmentId":"600e32f8-db90-472d-8c1f-f1b4ee117dd6","actId":"ad286ef2-8848-475b-aa4a-508fef03f6a6",
                 "cuit":"30712345678","amount":100000,"currency":"ARS","reason":"Incumplimiento comercial",
                 "decidedAt":"2026-10-17T09:10:00-03:00","externalRef":"MULTA-2026-00123",
                 "sourceViolationId":"b39f4b3b-acec-40ae-9a89-44beff40d35b","sourceModule":"M6","decision":"FINE"}}
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
        assertThat(first.businessKey).isEqualTo("habilitaciones:permitFeeGenerated:2ada0a54-7083-4717-8acc-1b88958795c7");
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
        assertThat(log.businessKey).isEqualTo("habilitaciones:commercialFineGenerated:3248f0c6-c43e-438d-9e83-f0c0acf9d510");
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
        catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("PERMIT_FEE","Tasa de habilitación",null,TaxConceptType.FEE,"M4"));
        IntegrationEventLog retried=consumer.consumePermitFee(event);
        assertThat(retried.id).isEqualTo(pending.id);
        assertThat(retried.errorMessage).startsWith(CoreM4Consumer.DUE_DATE_PENDING);
        assertThat(debts.count()).isEqualTo(debtsBefore);
    }

    @Test void historicalM4ReferenceRemainsPendingForManualReconciliation()throws Exception{
        String legacy=FEE.replace("2ada0a54-7083-4717-8acc-1b88958795c7","FEE-001");
        var event=json.readValue(legacy,new TypeReference<CoreM4Events.Envelope<CoreM4Events.PermitFeeGeneratedData>>(){});
        long before=obligations.count();IntegrationEventLog pending=consumer.consumePermitFee(event);
        assertThat(pending.status).isEqualTo(IntegrationEventStatus.FAILED);
        assertThat(pending.errorMessage).isEqualTo(CoreM4Consumer.SOURCE_ID_PENDING);
        assertThat(obligations.count()).isEqualTo(before);
    }

    @Test void reusedEventIdWithChangedEconomicFactIsRejected()throws Exception{
        var event=json.readValue(FEE,new TypeReference<CoreM4Events.Envelope<CoreM4Events.PermitFeeGeneratedData>>(){});
        consumer.consumePermitFee(event);
        var modified=new CoreM4Events.Envelope<>(event.eventId(),event.eventType(),event.eventVersion(),
            event.occurredAt(),event.sourceModule(),event.correlationId(),event.causationId(),
            new CoreM4Events.PermitFeeGeneratedData(event.data().id(),event.data().permitApplicationId(),
                event.data().establishmentId(),event.data().cuit(),new java.math.BigDecimal("99999"),"ARS"));
        assertThatThrownBy(()->consumer.consumePermitFee(modified)).isInstanceOfSatisfying(BusinessException.class,
            error->assertThat(error.code).isEqualTo("M4_EVENT_ID_CONFLICT"));
    }
}
