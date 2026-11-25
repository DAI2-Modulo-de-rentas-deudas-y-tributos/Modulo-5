package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** Keeps historical payload decoding separate from the confirmed integration contracts. */
@Service
class LegacyIntegrationReprocessAdapter {
    private final ObjectMapper json;
    private final ExternalObligationService obligations;
    private final TaxpayerIntegrationService taxpayers;
    private final TicketIntegrationService tickets;
    private final SocialBenefitIntegrationService benefits;
    private final PreliminaryM4Consumer m4;

    LegacyIntegrationReprocessAdapter(ObjectMapper json, ExternalObligationService obligations,
            TaxpayerIntegrationService taxpayers, TicketIntegrationService tickets,
            SocialBenefitIntegrationService benefits, PreliminaryM4Consumer m4) {
        this.json = json;
        this.obligations = obligations;
        this.taxpayers = taxpayers;
        this.tickets = tickets;
        this.benefits = benefits;
        this.m4 = m4;
    }

    boolean reprocessIfLegacy(IntegrationEventLog original) throws Exception {
        switch (original.eventType) {
            case "taxpayerCreated", "taxpayerUpdated", "taxpayerSynchronized" -> taxpayers.consume(
                    json.readValue(original.payload, new TypeReference<ApiDtos.EventEnvelope<ApiDtos.TaxpayerEventData>>() {}));
            case "ticketCreated" -> tickets.consume(
                    json.readValue(original.payload, new TypeReference<ApiDtos.EventEnvelope<ApiDtos.TicketEventData>>() {}));
            case "ticketUpdated" -> {
                if ("atencion-ciudadana".equals(original.sourceModule)) return false;
                tickets.consume(json.readValue(original.payload,
                        new TypeReference<ApiDtos.EventEnvelope<ApiDtos.TicketEventData>>() {}));
            }
            case "socialBenefitUpdated" -> {
                if (json.readTree(original.payload).path("data").has("benefitId")) return false;
                benefits.consume(json.readValue(original.payload,
                        new TypeReference<ApiDtos.EventEnvelope<ApiDtos.SocialBenefitEventData>>() {}));
            }
            case "permitFeeGenerated" -> {
                if (json.readTree(original.payload).has("eventId")) return false;
                m4.consumePermitFee(json.readValue(original.payload,
                        new TypeReference<PreliminaryM4Events.Envelope<PreliminaryM4Events.PermitFeeGeneratedData>>() {}));
            }
            case "commercialFineGenerated" -> {
                if (json.readTree(original.payload).has("eventId")) return false;
                m4.consumeCommercialFine(json.readValue(original.payload,
                        new TypeReference<PreliminaryM4Events.Envelope<PreliminaryM4Events.CommercialFineGeneratedData>>() {}));
            }
            case "infractionConfirmed" -> {
                if (json.readTree(original.payload).path("data").has("infractionId")) return false;
                ApiDtos.EventEnvelope<ApiDtos.ExternalObligationData> event = json.readValue(original.payload,
                        new TypeReference<ApiDtos.EventEnvelope<ApiDtos.ExternalObligationData>>() {});
                obligations.consumeInfraction(new ApiDtos.EventEnvelope<>(event.eventId(), event.eventType(),
                        event.occurredAt(), event.sourceModule(), new ApiDtos.InfractionData(
                                event.data().externalReferenceId(), event.data().taxpayerType(),
                                event.data().taxpayerExternalId(), event.data().amount(), event.data().dueDate())));
            }
            default -> { return false; }
        }
        return true;
    }
}
