package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Component
class CoreInboundEventDispatcher {
    private static final Set<String> ENVELOPE_FIELDS=Set.of("eventId","eventType","eventVersion","occurredAt","sourceModule","correlationId","causationId","data");
    private static final Set<String> REQUIRED_ENVELOPE_FIELDS=Set.of("eventId","eventType","occurredAt","sourceModule","data");
    private final ObjectMapper json;
    private final ConfirmedM1Consumer m1;
    private final ConfirmedM2Consumer m2;
    private final CoreM4Consumer m4;
    private final ConfirmedM7Consumer m7;
    private final ConfirmedM8Consumer m8;

    CoreInboundEventDispatcher(ObjectMapper json,ConfirmedM1Consumer m1,ConfirmedM2Consumer m2,CoreM4Consumer m4,
        ConfirmedM7Consumer m7,ConfirmedM8Consumer m8){this.json=json;this.m1=m1;this.m2=m2;this.m4=m4;this.m7=m7;this.m8=m8;}

    void dispatch(String payload) {
        JsonNode envelope=readEnvelope(payload);
        String eventType=envelope.path("eventType").asText();
        try {
            switch(eventType){
                case "citizenUpdated"->m1.consumeCitizen(json.readValue(envelope.traverse(),new TypeReference<ConfirmedInboundEvents.M1CoreEnvelope<ConfirmedInboundEvents.M1CitizenUpdatedData>>(){}));
                case "organizationRegistered"->m1.consumeOrganization(json.readValue(envelope.traverse(),new TypeReference<ConfirmedInboundEvents.M1CoreEnvelope<ConfirmedInboundEvents.M1OrganizationRegisteredData>>(){}));
                case "representationGranted","representationExpired"->m1.consumeRepresentation(json.readValue(envelope.traverse(),new TypeReference<ConfirmedInboundEvents.M1CoreEnvelope<ConfirmedInboundEvents.M1RepresentationData>>(){}));
                case "ticketUpdated"->m2.consume(json.treeToValue(envelope,ConfirmedInboundEvents.M2TicketUpdatedEvent.class));
                case "permitFeeGenerated"->{
                    IntegrationEventLog result=m4.consumePermitFee(json.readValue(envelope.traverse(),new TypeReference<CoreM4Events.Envelope<CoreM4Events.PermitFeeGeneratedData>>(){}));
                    if(result.status==IntegrationEventStatus.FAILED)throw new IllegalStateException("CORE_INBOUND_RETRY");
                }
                case "commercialFineGenerated"->{
                    IntegrationEventLog result=m4.consumeCommercialFine(json.readValue(envelope.traverse(),new TypeReference<CoreM4Events.Envelope<CoreM4Events.CommercialFineGeneratedData>>(){}));
                    if(result.status==IntegrationEventStatus.FAILED)throw new IllegalStateException("CORE_INBOUND_RETRY");
                }
                case "infractionConfirmed"->{
                    ExternalObligation result=m7.consume(json.treeToValue(envelope,ConfirmedInboundEvents.M7InfractionConfirmedEvent.class));
                    if(result.status==ExternalObligationStatus.ERROR)throw new IllegalStateException("CORE_INBOUND_RETRY");
                }
                case "socialBenefitUpdated"->m8.consume(json.treeToValue(envelope,ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent.class));
                default->throw new IllegalArgumentException("UNSUPPORTED_CORE_EVENT_TYPE");
            }
        } catch (IllegalArgumentException|BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            if(ex instanceof IllegalStateException state&&"CORE_INBOUND_RETRY".equals(state.getMessage()))throw state;
            throw new IllegalArgumentException("INVALID_CORE_EVENT_PAYLOAD",ex);
        }
    }

    private JsonNode readEnvelope(String payload){
        try {
            JsonNode envelope=json.readTree(payload);
            if(envelope==null||!envelope.isObject())throw new IllegalArgumentException();
            Set<String> actual=fields(envelope);
            if(!ENVELOPE_FIELDS.containsAll(actual)||!actual.containsAll(REQUIRED_ENVELOPE_FIELDS))throw new IllegalArgumentException();
            if(UUID.fromString(envelope.path("eventId").asText()).version()!=4)throw new IllegalArgumentException();
            if(!envelope.path("eventType").isTextual()||envelope.path("eventType").asText().isBlank())throw new IllegalArgumentException();
            if(!envelope.has("eventVersion"))((com.fasterxml.jackson.databind.node.ObjectNode)envelope).put("eventVersion","1.0");
            if(!"1.0".equals(envelope.path("eventVersion").asText()))throw new IllegalArgumentException();
            if(!envelope.path("sourceModule").isTextual()||!envelope.path("data").isObject())throw new IllegalArgumentException();
            OffsetDateTime.parse(envelope.path("occurredAt").asText());
            uuidOrNull(envelope.get("correlationId"));uuidOrNull(envelope.get("causationId"));
            return envelope;
        } catch (Exception ex) {
            throw new IllegalArgumentException("INVALID_CORE_ENVELOPE");
        }
    }

    private Set<String> fields(JsonNode node){
        java.util.HashSet<String> fields=new java.util.HashSet<>();node.fieldNames().forEachRemaining(fields::add);return fields;
    }
    private void uuidOrNull(JsonNode value){if(value!=null&&!value.isNull())UUID.fromString(value.asText());}
}

@Component
@ConditionalOnProperty(prefix="rentas",name="broker-adapter",havingValue="rabbitmq")
class CoreRabbitMqConsumer {
    private static final Logger log=LoggerFactory.getLogger(CoreRabbitMqConsumer.class);
    private final CoreInboundEventDispatcher dispatcher;

    CoreRabbitMqConsumer(CoreInboundEventDispatcher dispatcher){this.dispatcher=dispatcher;}

    @RabbitListener(queues="${rentas.core.amqp.queue:q.rentas}",
        autoStartup="${rentas.core.amqp.consumer-enabled:false}",ackMode="MANUAL")
    void receive(Message message,Channel channel,@Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            dispatcher.dispatch(new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8));
            channel.basicAck(deliveryTag,false);
        } catch (RuntimeException ex) {
            String payload=new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8);
            log.warn("CORE_INBOUND rejected eventId={} correlationId={} eventType={} exceptionType={}",
                OperationalLogSupport.uuidField(payload,"eventId"),OperationalLogSupport.uuidField(payload,"correlationId"),
                OperationalLogSupport.safeIdentifierField(payload,"eventType"),
                ex.getClass().getSimpleName(),OperationalLogSupport.sanitizedStackTrace(ex));
            channel.basicNack(deliveryTag,false,false);
        }
    }
}
