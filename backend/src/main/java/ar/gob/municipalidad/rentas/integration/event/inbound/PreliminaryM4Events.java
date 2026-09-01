package ar.gob.municipalidad.rentas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Preliminary M4 transport contract. Core still has to confirm the final envelope. */
public final class PreliminaryM4Events {
    private PreliminaryM4Events() {}

    public record Envelope<T>(@NotBlank String module,@NotBlank String event,@NotNull @Valid T data) {}

    public record PermitFeeGeneratedData(@NotBlank String id,@NotBlank String permitApplicationId,
        @NotBlank String establishmentId,@NotNull @Positive BigDecimal amount) {}

    public record CommercialFineGeneratedData(@NotBlank String id,String sourceViolationId,String sourceModule,
        @NotBlank String establishmentId,@NotBlank String actId,@NotNull @Positive BigDecimal amount,
        @NotBlank String reason,@NotNull OffsetDateTime decidedAt,@NotBlank String externalRef) {}
}
