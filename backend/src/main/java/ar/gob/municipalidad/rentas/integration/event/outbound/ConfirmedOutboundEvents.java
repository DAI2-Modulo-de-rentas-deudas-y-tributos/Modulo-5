package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ConfirmedOutboundEvents {
    private ConfirmedOutboundEvents() {}

    public interface TypedEvent { String eventType(); }
    public record Producer(String moduleId,String service) {}
    public record UpdatedBy(String type,String id) {}

    public record SimpleEnvelope<T>(String eventId,String eventType,OffsetDateTime timestamp,String sourceModule,T payload)
        implements TypedEvent {}
    public record M2Envelope<T>(String specVersion,String eventId,String eventType,OffsetDateTime occurredAt,
        Producer producer,String subject,T data) implements TypedEvent {}

    public record ExemptionRequestedPayload(String requestId,String citizenId,String conceptCode,String reason,
        BigDecimal requestedPercentage,LocalDate requestedFrom,LocalDate requestedUntil) {}
    public record UpdateExemptionStatusPayload(String requestId,String citizenId,String status,String exemptionId,
        String conceptCode,BigDecimal percentage,LocalDate validFrom,LocalDate validUntil,String reason) {}
    public record PaymentPlanRequestedPayload(String requestId,String citizenId,List<String> debtIds,
        BigDecimal totalDebt,int installments) {}
    public record UpdatePaymentPlanStatusPayload(String requestId,String citizenId,String status,String planId,
        Integer installments,BigDecimal totalAmount,String reason) {}
    public record OverdueDebtPayload(String debtId,String citizenId,String conceptCode,
        BigDecimal outstandingAmount,LocalDate dueDate) {}
    public record DebtSettledPayload(String debtId,String citizenId,String conceptCode,
        OffsetDateTime settledAt,BigDecimal outstandingBalance) {}

    public record M2UpdateTicketStatusData(Long ticketId,String updateType,String publicMessage,
        String internalMessage,Integer progress,JsonNode details,List<JsonNode> attachments,UpdatedBy updatedBy,
        OffsetDateTime statusChangedAt) {}
}
