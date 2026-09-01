package ar.gob.municipalidad.rentas;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
class ConfirmedContractValidator {
    private final Validator validator;
    ConfirmedContractValidator(Validator validator){this.validator=validator;}
    void validate(Object event){Set<ConstraintViolation<Object>> violations=validator.validate(event);if(!violations.isEmpty())throw new BusinessException("INVALID_EVENT",violations.iterator().next().getPropertyPath()+" "+violations.iterator().next().getMessage(),422);}
}
