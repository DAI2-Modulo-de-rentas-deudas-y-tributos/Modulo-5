package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
class PreliminaryM4EventMapper {
    private final ObjectMapper json;
    PreliminaryM4EventMapper(ObjectMapper json){this.json=json;}

    NormalizedIntegrationEvent normalize(PreliminaryM4Events.Envelope<?> event,String businessKey,OffsetDateTime receivedAt){
        return new NormalizedIntegrationEvent(businessKey,event.event(),receivedAt,event.module(),null,
            json.valueToTree(event.data()),null);
    }

    String businessKey(PreliminaryM4Events.Envelope<?> event,String id){return event.module()+":"+event.event()+":"+id;}
}
