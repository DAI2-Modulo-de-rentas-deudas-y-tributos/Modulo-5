package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Confirmed external contracts. They are deserialized before mapping to M5 models. */
public final class ConfirmedInboundEvents {
    private ConfirmedInboundEvents() {}

    public record Producer(@NotBlank String moduleId,@NotBlank String service) {}

    /** Core transport for M1. The older producer/subject envelope is kept only for legacy callers. */
    public record M1CoreEnvelope<T>(@NotNull UUID eventId,@NotBlank String eventType,
        @NotBlank String eventVersion,@NotNull OffsetDateTime occurredAt,@NotBlank String sourceModule,
        UUID correlationId,UUID causationId,@NotNull @Valid T data) {}

    public record M1CitizenUpdatedEvent(@NotBlank String eventId,@NotNull OffsetDateTime occurredAt,
        @NotBlank String eventType,@NotNull @Valid Producer producer,@NotBlank String subject,
        @NotNull @Valid M1CitizenUpdatedData data) {}
    public record M1CitizenUpdatedData(@NotNull Long citizenId,@NotBlank String updateType,
        @NotNull JsonNode details,@NotNull @Valid UpdatedBy updatedBy) {}
    public record UpdatedBy(@NotBlank String type,String id) {}
    public record M1CitizenRegisteredDetails(@NotBlank String dni,@NotBlank String cuil,@NotBlank String name,
        @NotBlank String lastname,@NotNull LocalDate birthdate,@NotBlank String state,JsonNode address) {}
    public record M1CitizenBlockedDetails(@NotBlank String reason,boolean reviewRequired) {}
    public record M1CitizenDeceasedDetails(@NotNull LocalDate deceasedAt,@NotBlank String source) {}

    public record M1OrganizationRegisteredEvent(@NotBlank String eventId,@NotNull OffsetDateTime occurredAt,
        @NotBlank String eventType,@NotNull @Valid Producer producer,@NotBlank String subject,
        @NotNull @Valid M1OrganizationRegisteredData data) {}
    public record M1OrganizationRegisteredData(@NotNull Long cuit,@NotBlank String taxId,@NotBlank String legalName,
        String tradeName,@NotBlank String type,@NotBlank String status,@NotNull @Valid M1OrganizationHolder holder) {}
    public record M1OrganizationHolder(@NotNull Long personId,@NotBlank String personType) {}

    public record M1RepresentationEvent(@NotBlank String eventId,@NotNull OffsetDateTime occurredAt,
        @NotBlank String eventType,@NotNull @Valid Producer producer,@NotBlank String subject,
        @NotNull @Valid M1RepresentationData data) {}
    public record M1RepresentationData(@NotNull Long representationId,@NotNull Long personId,@NotNull Long cuit,
        String scope,LocalDate from,LocalDate until,@NotBlank String status) {}

    public record M2TicketUpdatedEvent(@NotNull UUID eventId,@NotBlank String eventType,
        @NotBlank String eventVersion,@NotNull OffsetDateTime occurredAt,@NotBlank String sourceModule,
        UUID correlationId,UUID causationId,@NotNull @Valid M2TicketUpdatedData data) {}
    public record M2TicketUpdatedData(@NotNull UUID ticketId,@NotBlank String publicId,UUID citizenId,@NotNull Boolean isAnonymous,
        @NotBlank String responsibleAreaId,@NotBlank String updateType,@NotBlank String currentStatus,
        @NotBlank String currentPriority,@Min(0) @Max(100) Integer progress,String publicMessage,JsonNode details,List<@NotNull @Valid M2Attachment> attachments,
        @NotNull OffsetDateTime updatedAt) {}
    public record M2Attachment(@NotBlank String fileName,@NotBlank String contentType,
        @NotBlank String url,@Min(0) Long sizeBytes) {}

    public record M8SocialBenefitUpdatedEvent(@NotNull UUID eventId,@NotBlank String eventType,
        @NotBlank String eventVersion,@NotNull OffsetDateTime occurredAt,@NotBlank String sourceModule,
        UUID correlationId,UUID causationId,@NotNull @Valid M8SocialBenefitData data) {}
    public record M8SocialBenefitData(@NotNull UUID benefitId,@NotNull UUID citizenId,
        @NotNull UUID applicationId,@NotNull UUID programId,@NotNull UUID programEditionId,
        @NotBlank String programName,@NotNull UUID benefitTypeId,@NotBlank String type,
        @NotBlank String description,BigDecimal amount,@NotBlank String status,String reason,
        LocalDate startDate,LocalDate endDate,@NotNull OffsetDateTime updatedAt) {}

    public enum M7DebtorIdType { DNI, CUIT }
    public record M7InfractionConfirmedEvent(@NotNull UUID eventId,@NotBlank String eventType,
        @NotBlank String eventVersion,@NotNull OffsetDateTime occurredAt,@NotBlank String sourceModule,
        @NotNull UUID correlationId,UUID causationId,@NotNull @Valid M7InfractionData data) {
        public M7InfractionConfirmedEvent(UUID eventId,String eventType,OffsetDateTime occurredAt,
            String sourceModule,M7InfractionData data) {
            this(eventId,eventType,"1.0",occurredAt,sourceModule,null,null,data);
        }
    }
    public record M7InfractionData(@NotNull UUID infractionId,@NotBlank String debtorId,
        @NotNull M7DebtorIdType debtorIdType,@NotBlank String licensePlate,@NotBlank String infractionType,
        @NotNull OffsetDateTime infractionDateTime,@NotNull @Positive BigDecimal baseAmount,
        @NotNull List<@NotBlank String> aggravatingFactors,@NotNull @Positive BigDecimal finalAmount,
        @NotBlank String inspectorId,@NotNull @Valid M7Location location) {}
    public record M7Location(@NotBlank String street,@NotBlank String crossStreet,
        @NotNull BigDecimal latitude,@NotNull BigDecimal longitude) {}
}
