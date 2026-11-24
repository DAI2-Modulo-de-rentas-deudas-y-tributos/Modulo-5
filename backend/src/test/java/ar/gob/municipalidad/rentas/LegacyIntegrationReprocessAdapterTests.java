package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LegacyIntegrationReprocessAdapterTests {
    private final ExternalObligationService obligations = mock(ExternalObligationService.class);
    private final TaxpayerIntegrationService taxpayers = mock(TaxpayerIntegrationService.class);
    private final TicketIntegrationService tickets = mock(TicketIntegrationService.class);
    private final SocialBenefitIntegrationService benefits = mock(SocialBenefitIntegrationService.class);
    private final PreliminaryM4Consumer m4 = mock(PreliminaryM4Consumer.class);
    private final LegacyIntegrationReprocessAdapter adapter = new LegacyIntegrationReprocessAdapter(
            new ObjectMapper().findAndRegisterModules(), obligations, taxpayers, tickets, benefits, m4);

    @Test
    void legacyTicketCreatedUsesCompatibilityConsumer() throws Exception {
        IntegrationEventLog event = event("ticketCreated", "M2", """
                {"eventId":"bb904d0b-6dd3-437c-98be-5e315767b741","eventType":"ticketCreated",
                 "occurredAt":"2026-11-24T10:00:00-03:00","sourceModule":"M2",
                 "data":{"ticketId":101,"citizenId":4,"category":"RENTAS","description":"Consulta","priority":"HIGH"}}
                """);

        assertThat(adapter.reprocessIfLegacy(event)).isTrue();
        verify(tickets).consume(any(ApiDtos.EventEnvelope.class));
        verifyNoInteractions(taxpayers, benefits, m4, obligations);
    }

    @Test
    void legacyTaxpayerEventUsesCompatibilityConsumer() throws Exception {
        IntegrationEventLog event = event("taxpayerUpdated", "M1", """
                {"eventId":"bb904d0b-6dd3-437c-98be-5e315767b741","eventType":"taxpayerUpdated",
                 "occurredAt":"2026-11-24T10:00:00-03:00","sourceModule":"M1",
                 "data":{"taxpayerType":"CITIZEN","externalId":"4","displayName":"Citizen",
                 "status":"ACTIVE"}}
                """);

        assertThat(adapter.reprocessIfLegacy(event)).isTrue();
        verify(taxpayers).consume(any(ApiDtos.EventEnvelope.class));
        verifyNoInteractions(tickets, benefits, m4, obligations);
    }

    @Test
    void legacyTicketUpdateUsesCompatibilityConsumer() throws Exception {
        IntegrationEventLog event = event("ticketUpdated", "M2", """
                {"eventId":"bb904d0b-6dd3-437c-98be-5e315767b741","eventType":"ticketUpdated",
                 "occurredAt":"2026-11-24T10:00:00-03:00","sourceModule":"M2",
                 "data":{"ticketId":101,"citizenId":4,"category":"RENTAS","description":"Consulta","priority":"HIGH"}}
                """);

        assertThat(adapter.reprocessIfLegacy(event)).isTrue();
        verify(tickets).consume(any(ApiDtos.EventEnvelope.class));
        verifyNoInteractions(taxpayers, benefits, m4, obligations);
    }

    @Test
    void legacySocialBenefitWithoutConfirmedBenefitIdUsesCompatibilityConsumer() throws Exception {
        IntegrationEventLog event = event("socialBenefitUpdated", "M8", """
                {"eventId":"bb904d0b-6dd3-437c-98be-5e315767b741","eventType":"socialBenefitUpdated",
                 "occurredAt":"2026-11-24T10:00:00-03:00","sourceModule":"M8",
                 "data":{"externalBenefitId":"benefit-1","externalCitizenId":"4","benefitType":"ABL",
                 "status":"ACTIVE","discountPercentage":50,"validFrom":"2026-11-01","validUntil":"2026-12-01"}}
                """);

        assertThat(adapter.reprocessIfLegacy(event)).isTrue();
        verify(benefits).consume(any(ApiDtos.EventEnvelope.class));
        verifyNoInteractions(taxpayers, tickets, m4, obligations);
    }

    @Test
    void preliminaryM4PermitFeeUsesCompatibilityConsumer() throws Exception {
        IntegrationEventLog event = event("permitFeeGenerated", "M4", """
                {"module":"M4","event":"permitFeeGenerated",
                 "data":{"id":"fee-1","permitApplicationId":"application-1","establishmentId":"establishment-1","amount":80}}
                """);

        assertThat(adapter.reprocessIfLegacy(event)).isTrue();
        verify(m4).consumePermitFee(any(PreliminaryM4Events.Envelope.class));
        verifyNoInteractions(taxpayers, tickets, benefits, obligations);
    }

    @Test
    void preliminaryM4CommercialFineUsesCompatibilityConsumer() throws Exception {
        IntegrationEventLog event = event("commercialFineGenerated", "M4", """
                {"module":"M4","event":"commercialFineGenerated",
                 "data":{"id":"fine-1","establishmentId":"establishment-1","actId":"act-1","amount":80,
                 "reason":"Incumplimiento","decidedAt":"2026-11-24T10:00:00-03:00","externalRef":"fine-ref-1"}}
                """);

        assertThat(adapter.reprocessIfLegacy(event)).isTrue();
        verify(m4).consumeCommercialFine(any(PreliminaryM4Events.Envelope.class));
        verifyNoInteractions(taxpayers, tickets, benefits, obligations);
    }

    @Test
    void legacyInfractionWithoutInfractionIdUsesCompatibilityConsumer() throws Exception {
        IntegrationEventLog event = event("infractionConfirmed", "M7", """
                {"eventId":"bb904d0b-6dd3-437c-98be-5e315767b741","eventType":"infractionConfirmed",
                 "occurredAt":"2026-11-24T10:00:00-03:00","sourceModule":"M7",
                 "data":{"externalReferenceId":"infraction-1","taxpayerType":"CITIZEN",
                 "taxpayerExternalId":"30111222","amount":80,"dueDate":"2026-11-25"}}
                """);

        assertThat(adapter.reprocessIfLegacy(event)).isTrue();
        verify(obligations).consumeInfraction(any(ApiDtos.EventEnvelope.class));
        verifyNoInteractions(taxpayers, tickets, benefits, m4);
    }

    @Test
    void confirmedM2TicketUpdateRemainsWithConfirmedConsumer() throws Exception {
        IntegrationEventLog event = event("ticketUpdated", "atencion-ciudadana", """
                {"eventId":"bb904d0b-6dd3-437c-98be-5e315767b741","eventType":"ticketUpdated",
                 "sourceModule":"atencion-ciudadana"}
                """);

        assertThat(adapter.reprocessIfLegacy(event)).isFalse();
        verifyNoInteractions(tickets, taxpayers, benefits, m4, obligations);
    }

    @Test
    void unknownEventIsNotClaimedByLegacyAdapter() throws Exception {
        assertThat(adapter.reprocessIfLegacy(event("futureEvent", "m2", "{}"))).isFalse();
        verifyNoInteractions(tickets, taxpayers, benefits, m4, obligations);
    }

    private IntegrationEventLog event(String type, String source, String payload) {
        IntegrationEventLog event = new IntegrationEventLog();
        event.eventType = type;
        event.sourceModule = source;
        event.payload = payload;
        return event;
    }
}
