package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ConfirmedOutboundEvents {
    private ConfirmedOutboundEvents() {}

    public interface TypedEvent { String eventType(); }
    public record UpdatedBy(String type,String id) {}

    public record CoreEnvelope<T>(String eventId,String eventType,String eventVersion,OffsetDateTime occurredAt,
        String sourceModule,String correlationId,String causationId,T data) implements TypedEvent {}

    public record ExemptionRequestedPayload(UUID requestId,UUID citizenId,String conceptCode,String reason,
        BigDecimal requestedPercentage,LocalDate requestedFrom,LocalDate requestedUntil) {
        public ExemptionRequestedPayload {
            if(requestId==null||citizenId==null||blank(conceptCode)||blank(reason)||requestedPercentage==null
                ||requestedPercentage.signum()<=0||requestedPercentage.compareTo(new BigDecimal("100"))>0
                ||requestedFrom==null||requestedUntil==null||requestedUntil.isBefore(requestedFrom))
                throw new IllegalArgumentException("exemptionRequested requiere UUID, concepto, motivo, porcentaje y vigencia válidos");
        }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateExemptionStatusPayload(UUID requestId,UUID citizenId,String status,UUID exemptionId,
        String conceptCode,BigDecimal percentage,LocalDate validFrom,LocalDate validUntil,String reason) {
        public UpdateExemptionStatusPayload {
            if(requestId==null||citizenId==null||blank(conceptCode))
                throw new IllegalArgumentException("updateExemptionStatus requiere solicitud, ciudadano y concepto válidos");
            if("APPROVED".equals(status)) {
                if(exemptionId==null||percentage==null||percentage.signum()<=0
                    ||percentage.compareTo(new BigDecimal("100"))>0||validFrom==null
                    ||(validUntil!=null&&validUntil.isBefore(validFrom)))
                    throw new IllegalArgumentException("APPROVED requiere exención, porcentaje y vigencia válidos");
            } else if("REJECTED".equals(status)) {
                if(exemptionId!=null||percentage!=null||validFrom!=null||validUntil!=null||blank(reason))
                    throw new IllegalArgumentException("REJECTED requiere motivo y no admite datos de una exención otorgada");
            } else throw new IllegalArgumentException("Estado de exención no soportado");
        }
    }
    public record PaymentPlanRequestedPayload(String requestId,String citizenId,List<String> debtIds,
        BigDecimal totalDebt,int installments) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdatePaymentPlanStatusPayload(String requestId,String citizenId,String status,String planId,
        Integer installments,BigDecimal totalAmount,String reason) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OverdueDebtPayload(UUID debtId,UUID citizenId,String conceptCode,
        BigDecimal outstandingAmount,LocalDate dueDate) {
        public OverdueDebtPayload {
            if(debtId==null||citizenId==null||blank(conceptCode)||outstandingAmount==null
                ||outstandingAmount.signum()<=0||dueDate==null)
                throw new IllegalArgumentException("overdueDebt requiere UUID, concepto, saldo positivo y vencimiento");
        }
    }
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

    private static boolean blank(String value) { return value==null||value.isBlank(); }
}
