package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CoreRabbitMqConsumerTests {
    @Test void dispatchesOnlyValidatedEightFieldEnvelopeToExistingConsumer() {
        ConfirmedM2Consumer m2=mock(ConfirmedM2Consumer.class);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),mock(ConfirmedM1Consumer.class),m2,
            mock(CoreM4Consumer.class),mock(ConfirmedM7Consumer.class),mock(ConfirmedM8Consumer.class));
        dispatcher.dispatch(ticketUpdated());
        verify(m2).consume(any(ConfirmedInboundEvents.M2TicketUpdatedEvent.class));
    }

    @Test void rejectsExtraRootFieldsWithoutCallingAnyConsumer() {
        ConfirmedM2Consumer m2=mock(ConfirmedM2Consumer.class);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),mock(ConfirmedM1Consumer.class),m2,
            mock(CoreM4Consumer.class),mock(ConfirmedM7Consumer.class),mock(ConfirmedM8Consumer.class));
        String invalid=ticketUpdated().replace("\"data\":", "\"unexpected\":true,\"data\":");
        assertThatThrownBy(()->dispatcher.dispatch(invalid)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("INVALID_CORE_ENVELOPE");
        verifyNoInteractions(m2);
    }

    @Test void acceptsOptionalEnvelopeFieldsWhenCoreOmitsThem() {
        ConfirmedM2Consumer m2=mock(ConfirmedM2Consumer.class);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),mock(ConfirmedM1Consumer.class),m2,
            mock(CoreM4Consumer.class),mock(ConfirmedM7Consumer.class),mock(ConfirmedM8Consumer.class));
        String payload="""
            {"eventId":"%s","eventType":"ticketUpdated","occurredAt":"%s","sourceModule":"atencion-ciudadana","data":{"ticketId":"%s","publicId":"M2-100","citizenId":null,"isAnonymous":false,"responsibleAreaId":"M5","updateType":"STATUS_CHANGED","currentStatus":"IN_PROGRESS","currentPriority":"MEDIUM","progress":10,"updatedAt":"%s"}}
            """.formatted(UUID.randomUUID(),OffsetDateTime.now(),UUID.randomUUID(),OffsetDateTime.now());
        dispatcher.dispatch(payload);
        verify(m2).consume(argThat(event->"1.0".equals(event.eventVersion())&&event.correlationId()==null&&event.causationId()==null));
    }

    @Test void m4M7AndM8StillReachTheirExistingConsumersThroughTheListener() throws Exception {
        ConfirmedM1Consumer m1=mock(ConfirmedM1Consumer.class);
        ConfirmedM2Consumer m2=mock(ConfirmedM2Consumer.class);
        CoreM4Consumer m4=mock(CoreM4Consumer.class);
        ConfirmedM7Consumer m7=mock(ConfirmedM7Consumer.class);
        ConfirmedM8Consumer m8=mock(ConfirmedM8Consumer.class);
        IntegrationEventLog accepted=new IntegrationEventLog();accepted.status=IntegrationEventStatus.PROCESSED;
        when(m4.consumePermitFee(any())).thenReturn(accepted);
        ExternalObligation obligation=new ExternalObligation();obligation.status=ExternalObligationStatus.PROCESSED;
        when(m7.consume(any())).thenReturn(obligation);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),m1,m2,m4,m7,m8);
        acknowledge(dispatcher,envelope("permitFeeGenerated","habilitaciones","""
            {"id":"%s","permitApplicationId":"%s","establishmentId":"%s","cuit":"30712345678","amount":100,"currency":"ARS"}
            """.formatted(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID())),41);
        acknowledge(dispatcher,envelope("infractionConfirmed","transito","""
            {"infractionId":"%s","debtorId":"34567890","debtorIdType":"DNI","licensePlate":"AA123BB","infractionType":"SPEEDING","infractionDateTime":"2026-10-30T10:00:00-03:00","baseAmount":100,"aggravatingFactors":[],"finalAmount":100,"inspectorId":"INSPECTOR","location":{"street":"Principal","crossStreet":"Otra","latitude":-34.6,"longitude":-58.4}}
            """.formatted(UUID.randomUUID())),42);
        acknowledge(dispatcher,envelope("socialBenefitUpdated","desarrollo-social","""
            {"benefitId":"%s","citizenId":"%s","applicationId":"%s","programId":"%s","programEditionId":"%s","programName":"Programa","benefitTypeId":"%s","type":"TAX_EXEMPTION","description":"Exención","status":"APPROVED","updatedAt":"2026-10-30T10:00:00-03:00"}
            """.formatted(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID())),43);
        verify(m4).consumePermitFee(any());verify(m7).consume(any());verify(m8).consume(any());verifyNoInteractions(m1,m2);
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

    @Test void rejectedInboundLogsOnlyUuidIdentifiersAndSanitizedStackTrace() throws Exception {
        UUID eventId=UUID.randomUUID(),correlationId=UUID.randomUUID();
        String payload="{\"eventId\":\""+eventId+"\",\"correlationId\":\""+correlationId
            +"\",\"eventType\":\"ticketUpdated\",\"data\":{\"dni\":\"40111222\",\"cuit\":\"20-40111222-3\",\"token\":\"secret-token\"}}";
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        doThrow(new IllegalArgumentException("40111222 20-40111222-3 secret-token "+payload)).when(dispatcher).dispatch(payload);
        CoreRabbitMqConsumer listener=new CoreRabbitMqConsumer(dispatcher);
        Channel channel=mock(Channel.class);
        Logger logger=(Logger)LoggerFactory.getLogger(CoreRabbitMqConsumer.class);
        ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            listener.receive(new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties()),channel,31);
            verify(channel).basicNack(31,false,false);
            verify(channel,never()).basicNack(31,false,true);
            String output=logs.list.stream().map(event->event.getFormattedMessage()+" "+event.getThrowableProxy().getMessage())
                .reduce("",(left,right)->left+"\n"+right);
            assertThat(output).contains("eventId="+eventId,"correlationId="+correlationId,"eventType=ticketUpdated","exceptionType=IllegalArgumentException")
                .doesNotContain("40111222","20-40111222-3","secret-token",payload);
            assertThat(logs.list).anySatisfy(event->assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty());
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test void inboundLogOmitsUnsafeEventTypeValues() throws Exception {
        String payload="{\"eventType\":\"ticketUpdated\\nDNI=40111222\",\"data\":{}}";
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        doThrow(new IllegalArgumentException("unsafe input")).when(dispatcher).dispatch(payload);
        Logger logger=(Logger)LoggerFactory.getLogger(CoreRabbitMqConsumer.class);
        ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            new CoreRabbitMqConsumer(dispatcher).receive(
                new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties()),mock(Channel.class),32);
            String output=logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",String::concat);
            assertThat(output).contains("eventType=unavailable").doesNotContain("ticketUpdated","40111222");
        } finally {
            logger.detachAppender(logs);
        }
    }

    private Message message(long tag){MessageProperties properties=new MessageProperties();properties.setDeliveryTag(tag);return new Message(ticketUpdated().getBytes(StandardCharsets.UTF_8),properties);}
    private String envelope(String type,String source,String data){return """
        {"eventId":"%s","eventType":"%s","eventVersion":"1.0","occurredAt":"2026-10-30T10:00:00-03:00","sourceModule":"%s","correlationId":"%s","causationId":null,"data":%s}
        """.formatted(UUID.randomUUID(),type,source,UUID.randomUUID(),data);}
    private void acknowledge(CoreInboundEventDispatcher dispatcher,String payload,long tag) throws Exception {
        Channel channel=mock(Channel.class);
        new CoreRabbitMqConsumer(dispatcher).receive(new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties()),channel,tag);
        verify(channel).basicAck(tag,false);verify(channel,never()).basicNack(eq(tag),anyBoolean(),anyBoolean());
    }
    private String ticketUpdated(){return """
        {"eventId":"%s","eventType":"ticketUpdated","eventVersion":"1.0","occurredAt":"%s","sourceModule":"atencion-ciudadana","correlationId":null,"causationId":null,"data":{"ticketId":"%s","publicId":"M2-100","citizenId":null,"isAnonymous":false,"responsibleAreaId":"M5","updateType":"STATUS_CHANGED","currentStatus":"IN_PROGRESS","currentPriority":"MEDIUM","progress":10,"updatedAt":"%s"}}
        """.formatted(UUID.randomUUID(),OffsetDateTime.now(),UUID.randomUUID(),OffsetDateTime.now());}
}
