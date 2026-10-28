package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix="rentas",name="broker-adapter",havingValue="rabbitmq")
class RabbitMqEventPublisher implements EventPublisher {
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final String exchange;
    private final Duration confirmTimeout;

    RabbitMqEventPublisher(RabbitTemplate rabbit,ObjectMapper json,
        @org.springframework.beans.factory.annotation.Value("${rentas.core.amqp.exchange:muni.inbox}") String exchange,
        @org.springframework.beans.factory.annotation.Value("${rentas.core.amqp.confirm-timeout:5s}") Duration confirmTimeout,
        @org.springframework.beans.factory.annotation.Value("${spring.rabbitmq.username:}") String username,
        @org.springframework.beans.factory.annotation.Value("${spring.rabbitmq.password:}") String password) {
        if(username==null||username.isBlank()||password==null||password.isBlank()||"CHANGE_ME".equals(password))
            throw new IllegalStateException("CORE_RABBITMQ_CREDENTIALS_REQUIRED");
        rabbit.setReturnsCallback(returned -> {});
        this.rabbit=rabbit;this.json=json;this.exchange=exchange;this.confirmTimeout=confirmTimeout;
    }

    @Override public OutboxStatus publish(String eventType,String targetModule,String payload) {
        String eventId=eventId(payload);
        CorrelationData correlation=new CorrelationData(eventId);
        rabbit.convertAndSend(exchange,"",payload,message->{
            message.getMessageProperties().setMessageId(eventId);
            message.getMessageProperties().setContentType("application/json");
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        },correlation);
        CorrelationData.Confirm confirm;
        try {
            confirm=correlation.getFuture().get(confirmTimeout.toMillis(),TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CORE_BROKER_CONFIRM_INTERRUPTED");
        } catch (Exception ex) {
            throw new IllegalStateException("CORE_BROKER_CONFIRM_FAILED");
        }
        if(!confirm.isAck()||correlation.getReturned()!=null)throw new IllegalStateException("CORE_BROKER_CONFIRM_REJECTED");
        return OutboxStatus.BROKER_CONFIRMED;
    }

    private String eventId(String payload) {
        try {
            JsonNode envelope=json.readTree(payload);
            UUID id=UUID.fromString(envelope.path("eventId").asText());
            if(id.version()!=4||!"rentas".equals(envelope.path("sourceModule").asText()))throw new IllegalArgumentException();
            return id.toString();
        } catch (Exception ex) {
            throw new IllegalArgumentException("INVALID_CORE_ENVELOPE");
        }
    }
}
