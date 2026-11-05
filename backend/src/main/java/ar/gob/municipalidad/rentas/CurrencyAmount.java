package ar.gob.municipalidad.rentas;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CurrencyAmount.Validator.class)
public @interface CurrencyAmount {
    String message() default "Debe tener como máximo 2 decimales";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<CurrencyAmount, BigDecimal> {
        @Override public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
            return value == null || value.stripTrailingZeros().scale() <= 2;
        }
    }
}
