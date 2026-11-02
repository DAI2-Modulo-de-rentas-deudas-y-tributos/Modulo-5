package ar.gob.municipalidad.rentas;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

/** An explicit local policy is required before an M4 obligation becomes collectible. */
@Component
class M4DueDatePolicy {
    private final Environment environment;

    M4DueDatePolicy(Environment environment){this.environment=environment;}

    Optional<LocalDate> dueDate(ExternalObligationType type,OffsetDateTime occurredAt){
        String property=switch(type){
            case PERMIT_FEE->"rentas.m4.due-days.permit-fee";
            case COMMERCIAL_FINE->"rentas.m4.due-days.commercial-fine";
            default->throw new IllegalArgumentException("No es un origen M4");
        };
        String configured=environment.getProperty(property);
        if(configured==null||configured.isBlank())return Optional.empty();
        try{
            int days=Integer.parseInt(configured);
            CatalogService.require(days>=0&&days<=3650,"INVALID_M4_DUE_DATE_POLICY",
                "El plazo configurado para "+type+" debe estar entre 0 y 3650 días");
            return Optional.of(occurredAt.toInstant().atZone(ZoneId.of("America/Argentina/Buenos_Aires"))
                .toLocalDate().plusDays(days));
        }catch(NumberFormatException ex){
            throw new BusinessException("INVALID_M4_DUE_DATE_POLICY","El plazo configurado para "+type+" no es un número entero",422);
        }
    }
}
