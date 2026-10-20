package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Simula la entrega de Core sin conectar RabbitMQ; disponible solo en desarrollo. */
@Hidden
@RestController
@RequestMapping("/api/v1/dev/integrations/m4")
@Profile("!prod")
@ConditionalOnProperty(name="rentas.security.dev-mode",havingValue="true")
class DevM4EventController {
    private final CoreM4Consumer consumer;
    private final ObjectMapper json;
    DevM4EventController(CoreM4Consumer consumer,ObjectMapper json){this.consumer=consumer;this.json=json;}

    @PostMapping("/core-events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('SUPERVISOR','TECHNICAL')")
    ApiDtos.IntegrationEventResponse simulate(@RequestBody JsonNode event){
        try{
            String type=event.path("eventType").asText();
            IntegrationEventLog result=switch(type){
                case "permitFeeGenerated"->consumer.consumePermitFee(json.convertValue(event,new TypeReference<CoreM4Events.Envelope<CoreM4Events.PermitFeeGeneratedData>>(){}));
                case "commercialFineGenerated"->consumer.consumeCommercialFine(json.convertValue(event,new TypeReference<CoreM4Events.Envelope<CoreM4Events.CommercialFineGeneratedData>>(){}));
                default->throw new BusinessException("UNSUPPORTED_EVENT_TYPE","Evento M4 no soportado",422);
            };
            return ApiResponses.of(result);
        }catch(IllegalArgumentException ex){throw new BusinessException("INVALID_EVENT","Evento M4 inválido",422);}
    }
}
