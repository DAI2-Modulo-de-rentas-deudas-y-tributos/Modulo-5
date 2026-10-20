package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ConfirmedOutboundEvents {
    private ConfirmedOutboundEvents() {}

    public interface TypedEvent { String eventType(); }
    public record UpdatedBy(String type,String id) {}

    public record CoreEnvelope<T>(String eventId,String eventType,String eventVersion,OffsetDateTime occurredAt,
        String sourceModule,String correlationId,String causationId,T data) implements TypedEvent {}

    public record ExemptionRequestedPayload(String requestId,String citizenId,String conceptCode,String reason,
        BigDecimal requestedPercentage,LocalDate requestedFrom,LocalDate requestedUntil) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateExemptionStatusPayload(String requestId,String citizenId,String status,String exemptionId,
        String conceptCode,BigDecimal percentage,LocalDate validFrom,LocalDate validUntil,String reason) {}
    public record PaymentPlanRequestedPayload(String requestId,String citizenId,List<String> debtIds,
        BigDecimal totalDebt,int installments) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdatePaymentPlanStatusPayload(String requestId,String citizenId,String status,String planId,
        Integer installments,BigDecimal totalAmount,String reason) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OverdueDebtPayload(String debtId,String citizenId,String conceptCode,
        BigDecimal outstandingAmount,LocalDate dueDate) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DebtSettledPayload(String debtId,String citizenId,String conceptCode,String originType,
        String originId,String infractionId,OffsetDateTime settledAt,BigDecimal remainingBalance,
        BigDecimal outstandingBalance) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExternalPaymentRegisteredPayload(String paymentId,String debtId,String originType,
        String originId,String infractionId,BigDecimal amountApplied,OffsetDateTime paidAt,
        BigDecimal remainingBalance) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaymentReversedPayload(String paymentId,String debtId,String originType,String originId,
        String infractionId,BigDecimal reversedAmount,OffsetDateTime reversedAt,
        BigDecimal remainingBalance,String reason) {}

    public record M2UpdateTicketStatusData(String ticketId,String updateType,String publicMessage,
        String internalMessage,Integer progress,JsonNode details,List<JsonNode> attachments,UpdatedBy updatedBy,
        OffsetDateTime updateOccurredAt) {}
}
