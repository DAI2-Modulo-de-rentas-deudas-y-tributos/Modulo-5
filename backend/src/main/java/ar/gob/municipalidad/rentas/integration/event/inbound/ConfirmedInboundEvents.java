package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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

    public record M2TicketUpdatedEvent(@NotBlank String specVersion,@NotBlank String eventId,
        @NotBlank String eventType,@NotNull OffsetDateTime occurredAt,@NotNull @Valid Producer producer,
        @NotBlank String subject,@NotNull @Valid M2TicketUpdatedData data) {}
    public record M2TicketUpdatedData(@NotNull Long ticketId,Long citizenId,boolean isAnonymous,
        @NotBlank String responsibleAreaId,@NotBlank String updateType,@NotBlank String currentStatus,
        @NotBlank String currentPriority,String publicMessage,JsonNode details,List<@Valid M2Attachment> attachments,
        @NotNull OffsetDateTime updatedAt) {}
    public record M2Attachment(@NotNull Long attachmentId,@NotBlank String fileName,@NotBlank String contentType,
        @NotBlank String url,@NotNull Long sizeBytes) {}

    public record M8SocialBenefitUpdatedEvent(@NotBlank String eventId,@NotBlank String eventType,
        @NotNull OffsetDateTime timestamp,@NotBlank String sourceModule,@NotNull @Valid M8SocialBenefitData payload) {}
    public record M8SocialBenefitData(@NotBlank String benefitId,@NotBlank String citizenId,
        @NotBlank String applicationId,@NotBlank String programId,@NotBlank String programName,
        @NotEmpty List<@Valid M8Benefit> benefits,@NotBlank String status,@NotNull LocalDate startDate,
        LocalDate endDate,@NotNull OffsetDateTime updatedAt) {}
    public record M8Benefit(@NotBlank String type,BigDecimal amount) {}

    public enum M7DebtorIdType { DNI, CUIT }
    public record M7InfractionConfirmedEvent(@NotNull UUID eventId,@NotBlank String eventType,
        @NotNull OffsetDateTime occurredAt,@NotBlank String sourceModule,@NotNull @Valid M7InfractionData data) {}
    public record M7InfractionData(@NotNull UUID infractionId,@NotBlank String debtorId,
        @NotNull M7DebtorIdType debtorIdType,@NotBlank String licensePlate,@NotBlank String infractionType,
        @NotNull OffsetDateTime infractionDateTime,@NotNull @Positive BigDecimal baseAmount,
        @NotNull List<@NotBlank String> aggravatingFactors,@NotNull @Positive BigDecimal finalAmount,
        @NotBlank String inspectorId,@NotNull @Valid M7Location location) {}
    public record M7Location(@NotBlank String street,@NotBlank String crossStreet,
        @NotNull BigDecimal latitude,@NotNull BigDecimal longitude) {}
}
