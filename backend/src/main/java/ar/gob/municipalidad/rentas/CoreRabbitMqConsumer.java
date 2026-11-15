package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("INVALID_CORE_EVENT_PAYLOAD",ex);
        } catch (DataAccessException ex) {
            throw ex;
        } catch (Exception ex) {
            if(ex instanceof IllegalStateException state&&"CORE_INBOUND_RETRY".equals(state.getMessage()))throw state;
            throw new IllegalStateException("CORE_INBOUND_PROCESSING_FAILED",ex);
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
@ConditionalOnProperty(prefix="rentas.core.amqp",name="consumer-enabled",havingValue="true")
class CoreRabbitMqConsumer {
    private static final Logger log=LoggerFactory.getLogger(CoreRabbitMqConsumer.class);
    private final CoreInboundEventDispatcher dispatcher;
    private final RabbitTemplate rabbit;
    private final String retryExchange;
    private final String retryQueue1;
    private final String retryQueue2;
    private final String retryQueue3;
    private final long confirmTimeoutMillis;

    CoreRabbitMqConsumer(CoreInboundEventDispatcher dispatcher,RabbitTemplate rabbit,
        @org.springframework.beans.factory.annotation.Value("${rentas.core.amqp.retry-exchange:rentas.retry}") String retryExchange,
        @org.springframework.beans.factory.annotation.Value("${rentas.core.amqp.retry-queue-1:q.rentas.retry.1}") String retryQueue1,
        @org.springframework.beans.factory.annotation.Value("${rentas.core.amqp.retry-queue-2:q.rentas.retry.2}") String retryQueue2,
        @org.springframework.beans.factory.annotation.Value("${rentas.core.amqp.retry-queue-3:q.rentas.retry.3}") String retryQueue3,
        @org.springframework.beans.factory.annotation.Value("${rentas.core.amqp.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        this.dispatcher=dispatcher;this.rabbit=rabbit;this.retryExchange=retryExchange;
        this.retryQueue1=retryQueue1;this.retryQueue2=retryQueue2;this.retryQueue3=retryQueue3;this.confirmTimeoutMillis=confirmTimeoutMillis;
    }

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
            String retryQueue=retryable(ex)?nextRetryQueue(message):null;
            if(retryQueue==null){channel.basicNack(deliveryTag,false,false);return;}
            try {
                Message retry=MessageBuilder.fromMessage(message).build();
                retry.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                CorrelationData correlation=new CorrelationData(OperationalLogSupport.uuidField(payload,"eventId")+retryQueue);
                rabbit.send(retryExchange,retryQueue,retry,correlation);
                CorrelationData.Confirm confirm=correlation.getFuture().get(confirmTimeoutMillis,TimeUnit.MILLISECONDS);
                if(confirm.isAck()&&correlation.getReturned()==null)channel.basicAck(deliveryTag,false);
                else channel.basicNack(deliveryTag,false,false);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                channel.basicNack(deliveryTag,false,false);
            } catch (Exception publishFailure) {
                log.warn("CORE_INBOUND retry_publish_failed eventId={} eventType={} exceptionType={}",
                    OperationalLogSupport.uuidField(payload,"eventId"),OperationalLogSupport.safeIdentifierField(payload,"eventType"),
                    publishFailure.getClass().getSimpleName());
                channel.basicNack(deliveryTag,false,false);
            }
        }
    }

    private boolean retryable(RuntimeException ex){return !(ex instanceof IllegalArgumentException||ex instanceof BusinessException);}

    private String nextRetryQueue(Message message){
        Object lastDeathQueue=message.getMessageProperties().getHeaders().get("x-last-death-queue");
        String last=lastDeathQueue==null?null:lastDeathQueue.toString();
        if(retryQueue3.equals(last))return null;
        if(retryQueue2.equals(last))return retryQueue3;
        return retryQueue1.equals(last)?retryQueue2:retryQueue1;
    }
}
