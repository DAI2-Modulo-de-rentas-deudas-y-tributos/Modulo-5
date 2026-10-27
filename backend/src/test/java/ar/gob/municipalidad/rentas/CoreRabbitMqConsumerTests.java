package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CoreRabbitMqConsumerTests {
    @Test void dispatchesOnlyValidatedEightFieldEnvelopeToExistingConsumer() {
        ConfirmedM2Consumer m2=mock(ConfirmedM2Consumer.class);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),m2,
            mock(CoreM4Consumer.class),mock(ConfirmedM7Consumer.class),mock(ConfirmedM8Consumer.class));
        dispatcher.dispatch(ticketUpdated());
        verify(m2).consume(any(ConfirmedInboundEvents.M2TicketUpdatedEvent.class));
    }

    @Test void rejectsExtraRootFieldsWithoutCallingAnyConsumer() {
        ConfirmedM2Consumer m2=mock(ConfirmedM2Consumer.class);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),m2,
            mock(CoreM4Consumer.class),mock(ConfirmedM7Consumer.class),mock(ConfirmedM8Consumer.class));
        String invalid=ticketUpdated().replace("\"data\":", "\"unexpected\":true,\"data\":");
        assertThatThrownBy(()->dispatcher.dispatch(invalid)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("INVALID_CORE_ENVELOPE");
        verifyNoInteractions(m2);
    }

    @Test void acceptsOptionalEnvelopeFieldsWhenCoreOmitsThem() {
        ConfirmedM2Consumer m2=mock(ConfirmedM2Consumer.class);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),m2,
            mock(CoreM4Consumer.class),mock(ConfirmedM7Consumer.class),mock(ConfirmedM8Consumer.class));
        String payload="""
            {"eventId":"%s","eventType":"ticketUpdated","occurredAt":"%s","sourceModule":"atencion-ciudadana","data":{"ticketId":"%s","publicId":"M2-100","citizenId":null,"isAnonymous":false,"responsibleAreaId":"M5","updateType":"STATUS_CHANGED","currentStatus":"IN_PROGRESS","currentPriority":"MEDIUM","progress":10,"updatedAt":"%s"}}
            """.formatted(UUID.randomUUID(),OffsetDateTime.now(),UUID.randomUUID(),OffsetDateTime.now());
        dispatcher.dispatch(payload);
        verify(m2).consume(argThat(event->"1.0".equals(event.eventVersion())&&event.correlationId()==null&&event.causationId()==null));
    }

    @Test void acknowledgesOnlyAfterDispatcherReturnsAndNeverRequeuesFailures() throws Exception {
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        CoreRabbitMqConsumer listener=new CoreRabbitMqConsumer(dispatcher);
        Channel channel=mock(Channel.class);
        listener.receive(message(29),channel,29);
        verify(channel).basicAck(29,false);

        doThrow(new IllegalArgumentException("validation details" )).when(dispatcher).dispatch("invalid");
        listener.receive(new Message("invalid".getBytes(StandardCharsets.UTF_8),new MessageProperties()),channel,30);
        verify(channel).basicNack(30,false,false);
        verify(channel,never()).basicNack(30,false,true);
    }

    private Message message(long tag){MessageProperties properties=new MessageProperties();properties.setDeliveryTag(tag);return new Message(ticketUpdated().getBytes(StandardCharsets.UTF_8),properties);}
    private String ticketUpdated(){return """
        {"eventId":"%s","eventType":"ticketUpdated","eventVersion":"1.0","occurredAt":"%s","sourceModule":"atencion-ciudadana","correlationId":null,"causationId":null,"data":{"ticketId":"%s","publicId":"M2-100","citizenId":null,"isAnonymous":false,"responsibleAreaId":"M5","updateType":"STATUS_CHANGED","currentStatus":"IN_PROGRESS","currentPriority":"MEDIUM","progress":10,"updatedAt":"%s"}}
        """.formatted(UUID.randomUUID(),OffsetDateTime.now(),UUID.randomUUID(),OffsetDateTime.now());}
}
