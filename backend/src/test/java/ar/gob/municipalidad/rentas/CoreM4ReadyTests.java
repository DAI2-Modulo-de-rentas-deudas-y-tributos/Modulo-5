package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(properties={"rentas.m4.due-days.permit-fee=14","rentas.m4.due-days.commercial-fine=7"})
@Transactional
class CoreM4ReadyTests {
    @Autowired CoreM4Consumer consumer;
    @Autowired CatalogService catalog;
    @Autowired ExternalObligationRepository obligations;
    @Autowired DebtRepository debts;
    @Autowired TaxpayerRepository taxpayers;

    @Test void permitCreatesOneDebtWithOriginalUuidAndExplicitDueRule(){
        String cuit="30712345678";
        TaxpayerReference organization=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
            TaxpayerType.ORGANIZATION,"M4-FEE-ORG",null,cuit,"Establecimiento M4"));
        TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
            "PERMIT_FEE","Tasa habilitación",null,TaxConceptType.FEE,"M4"));
        UUID originId=UUID.randomUUID(),applicationId=UUID.randomUUID(),establishmentId=UUID.randomUUID();
        OffsetDateTime occurredAt=OffsetDateTime.parse("2026-10-20T10:00:00-03:00");
        var event=new CoreM4Events.Envelope<>(UUID.randomUUID(),"permitFeeGenerated","1.0",occurredAt,
            "habilitaciones",UUID.randomUUID(),null,new CoreM4Events.PermitFeeGeneratedData(
                originId.toString(),applicationId.toString(),establishmentId.toString(),cuit,new BigDecimal("50000"),"ARS"));
        long before=debts.count();
        IntegrationEventLog first=consumer.consumePermitFee(event);
        assertThat(first.status).isEqualTo(IntegrationEventStatus.PROCESSED);
        assertThat(first.payload).contains(applicationId.toString(),establishmentId.toString(),cuit);
        ExternalObligation obligation=obligations.findBySourceModuleAndExternalTypeAndExternalReferenceId(
            "habilitaciones",ExternalObligationType.PERMIT_FEE,originId.toString()).orElseThrow();
        assertThat(obligation.sourceEventId).isEqualTo(event.eventId());
        assertThat(obligation.taxpayerId).isEqualTo(organization.id);
        assertThat(obligation.taxConceptId).isEqualTo(concept.id);
        assertThat(obligation.amount).isEqualByComparingTo("50000.00");
        assertThat(obligation.dueDate).isEqualTo(LocalDate.parse("2026-11-03"));
        Debt debt=debts.findAll().stream().filter(d->obligation.id.equals(d.externalObligationId)).findFirst().orElseThrow();
        assertThat(debt.integrationId).isNotNull();assertThat(debt.id).isNotNull();
        assertThat(debt.dueDate).isEqualTo(obligation.dueDate);
        assertThat(consumer.consumePermitFee(event).id).isEqualTo(first.id);
        var duplicateOrigin=new CoreM4Events.Envelope<>(UUID.randomUUID(),event.eventType(),event.eventVersion(),
            event.occurredAt(),event.sourceModule(),event.correlationId(),null,event.data());
        assertThat(consumer.consumePermitFee(duplicateOrigin).status).isEqualTo(IntegrationEventStatus.IGNORED);
        assertThat(debts.count()).isEqualTo(before+1);
        assertThat(debts.findById(debt.id).orElseThrow().dueDate).isEqualTo(LocalDate.parse("2026-11-03"));
        assertThat(taxpayers.findById(organization.id).orElseThrow().canonicalCitizenId).isNull();
    }

    @Test void commercialFinePreservesEnvironmentalTraceAndOwnDueRule(){
        String cuit="30712345679";
        catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.ORGANIZATION,"M4-FINE-ORG",null,cuit,"Comercio M4"));
        catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("COMMERCIAL_FINE","Multa comercial",null,TaxConceptType.FINE,"M4"));
        UUID fineId=UUID.randomUUID(),actId=UUID.randomUUID(),violationId=UUID.randomUUID();
        var event=new CoreM4Events.Envelope<>(UUID.randomUUID(),"commercialFineGenerated","1.0",
            OffsetDateTime.parse("2026-10-20T10:00:00-03:00"),"habilitaciones",UUID.randomUUID(),UUID.randomUUID(),
            new CoreM4Events.CommercialFineGeneratedData(fineId.toString(),UUID.randomUUID().toString(),
                actId.toString(),cuit,new BigDecimal("25000"),"ARS","Incumplimiento ambiental",
                OffsetDateTime.parse("2026-10-20T09:25:00-03:00"),"MULTA-2026-00125",
                violationId.toString(),"M6","FINE"));
        IntegrationEventLog log=consumer.consumeCommercialFine(event);
        assertThat(log.status).isEqualTo(IntegrationEventStatus.PROCESSED);
        assertThat(log.payload).contains(actId.toString(),violationId.toString(),"MULTA-2026-00125","\"decision\":\"FINE\"");
        ExternalObligation obligation=obligations.findBySourceModuleAndExternalTypeAndExternalReferenceId(
            "habilitaciones",ExternalObligationType.COMMERCIAL_FINE,fineId.toString()).orElseThrow();
        assertThat(obligation.amount).isEqualByComparingTo("25000.00");
        assertThat(obligation.dueDate).isEqualTo(LocalDate.parse("2026-10-27"));
        assertThat(debts.findAll().stream().filter(d->obligation.id.equals(d.externalObligationId))).hasSize(1);
    }
}
