package ar.gob.municipalidad.rentas;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Adaptador HTTP exclusivo de desarrollo para simular la entrega del broker de M2. */
@Hidden
@RestController
@RequestMapping("/api/v1/dev/integrations/m2")
@ConditionalOnProperty(name="rentas.security.dev-mode",havingValue="true")
class DevM2EventController {
    private final TicketIntegrationService consumer;

    DevM2EventController(TicketIntegrationService consumer){this.consumer=consumer;}

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('SUPERVISOR','TECHNICAL')")
    ApiDtos.TicketResponse simulate(@Valid @RequestBody ApiDtos.EventEnvelope<ApiDtos.TicketEventData> event){
        return ApiResponses.of(consumer.consume(event));
    }
}
