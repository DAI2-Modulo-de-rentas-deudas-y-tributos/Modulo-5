package ar.gob.municipalidad.rentas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Formato de trabajo de M4 sobre el envelope de Core; CUIT de 11 dígitos confirmado. */
public final class CoreM4Events {
    private CoreM4Events() {}

    public record Envelope<T>(@NotNull UUID eventId,@NotBlank String eventType,String eventVersion,
        @NotNull OffsetDateTime occurredAt,@NotBlank String sourceModule,UUID correlationId,
        UUID causationId,@NotNull @Valid T data) {}

    public record PermitFeeGeneratedData(@NotBlank String id,@NotBlank String permitApplicationId,
        @NotBlank String establishmentId,@NotBlank @Pattern(regexp="\\d{11}") String cuit,@NotNull @Positive BigDecimal amount,
        @NotBlank String currency) {}

    public record CommercialFineGeneratedData(@NotBlank String id,@NotBlank String establishmentId,
        @NotBlank String actId,@NotBlank @Pattern(regexp="\\d{11}") String cuit,@NotNull @Positive BigDecimal amount,
        @NotBlank String currency,@NotBlank String reason,@NotNull OffsetDateTime decidedAt,
        @NotBlank String externalRef,String sourceViolationId,String sourceModule) {}
}
