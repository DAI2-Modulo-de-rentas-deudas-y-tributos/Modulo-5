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
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

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

    @Test void dispatcherAuditsPermanentRejectionsButLeavesTransientRetriesToCorePolicy() {
        String payload=ticketUpdated();
        ConfirmedM2Consumer m2=mock(ConfirmedM2Consumer.class);
        InboundRejectionAudit audit=mock(InboundRejectionAudit.class);
        BusinessException invalid=new BusinessException("INVALID_EVENT","invalid test event",422);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),
            mock(ConfirmedM1Consumer.class),m2,mock(CoreM4Consumer.class),mock(ConfirmedM7Consumer.class),mock(ConfirmedM8Consumer.class),audit);
        doThrow(invalid).when(m2).consume(any());

        assertThatThrownBy(()->dispatcher.dispatch(payload)).isSameAs(invalid);
        verify(audit).record(payload,invalid);

        reset(m2,audit);
        doThrow(new CoreInboundRetryException("M4_TEMPORARY_FAILURE")).when(m2).consume(any());
        assertThatThrownBy(()->dispatcher.dispatch(payload)).isInstanceOf(CoreInboundRetryException.class);
        verifyNoInteractions(audit);
    }

    @Test void ticketCreatedIsNotPartOfTheConfirmedCoreDispatcherContract() {
        ConfirmedM1Consumer m1=mock(ConfirmedM1Consumer.class);ConfirmedM2Consumer m2=mock(ConfirmedM2Consumer.class);
        CoreM4Consumer m4=mock(CoreM4Consumer.class);ConfirmedM7Consumer m7=mock(ConfirmedM7Consumer.class);
        ConfirmedM8Consumer m8=mock(ConfirmedM8Consumer.class);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),m1,m2,m4,m7,m8);
        String unsupported="""
            {"eventId":"%s","eventType":"ticketCreated","eventVersion":"1.0","occurredAt":"2026-11-15T10:00:00-03:00","sourceModule":"atencion-ciudadana","data":{}}
            """.formatted(UUID.randomUUID());

        assertThatThrownBy(()->dispatcher.dispatch(unsupported)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("UNSUPPORTED_CORE_EVENT_TYPE");
        verifyNoInteractions(m1,m2,m4,m7,m8);
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

    @Test void acknowledgesSuccessAndSendsPermanentInvalidPayloadDirectlyToDeadLetter() throws Exception {
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        CoreRabbitMqConsumer listener=listener(dispatcher);
        Channel channel=mock(Channel.class);
        listener.receive(message(29),channel,29);
        verify(channel).basicAck(29,false);

        doThrow(new IllegalArgumentException("validation details" )).when(dispatcher).dispatch("invalid");
        listener.receive(new Message("invalid".getBytes(StandardCharsets.UTF_8),new MessageProperties()),channel,30);
        verify(channel).basicNack(30,false,false);
        verify(channel,never()).basicNack(30,false,true);
    }

    @Test void transientFailureIsConfirmedToFirstRetryQueueBeforeOriginalIsAcknowledged() throws Exception {
        String eventId=UUID.randomUUID().toString();
        String payload="{\"eventId\":\""+eventId+"\",\"eventType\":\"ticketUpdated\",\"data\":{}}";
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        doThrow(new IllegalStateException("CORE_INBOUND_RETRY")).when(dispatcher).dispatch(payload);
        RabbitTemplate rabbit=confirmedPublisher();
        Message original=new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties());
        original.getMessageProperties().setMessageId(eventId);
        Channel channel=mock(Channel.class);

        listener(dispatcher,rabbit).receive(original,channel,40);

        verify(rabbit).send(eq("rentas.retry"),eq("q.rentas.retry.1"),argThat(message->
            eventId.equals(message.getMessageProperties().getMessageId())&&payload.equals(new String(message.getBody(),StandardCharsets.UTF_8))),any(CorrelationData.class));
        verify(channel).basicAck(40,false);
        verify(channel,never()).basicNack(eq(40),anyBoolean(),anyBoolean());
    }

    @Test void coreManagedTransientFailureNacksWithoutPublishingToLocalRetryQueues() throws Exception {
        String payload="{\"eventId\":\""+UUID.randomUUID()+"\",\"eventType\":\"ticketUpdated\",\"data\":{}}";
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        doThrow(new IllegalStateException("CORE_INBOUND_RETRY")).when(dispatcher).dispatch(payload);
        RabbitTemplate rabbit=mock(RabbitTemplate.class);
        Channel channel=mock(Channel.class);

        listener(dispatcher,rabbit,"core-managed").receive(
            new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties()),channel,45);

        verify(channel).basicNack(45,false,false);
        verify(channel,never()).basicAck(45,false);
        verifyNoInteractions(rabbit);
    }

    @Test void threeTransientRetriesUseThreeRetryQueuesThenDeadLetter() throws Exception {
        String payload="{\"eventId\":\""+UUID.randomUUID()+"\",\"eventType\":\"ticketUpdated\",\"data\":{}}";
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        doThrow(new IllegalStateException("CORE_INBOUND_RETRY")).when(dispatcher).dispatch(payload);
        RabbitTemplate rabbit=confirmedPublisher();
        Channel channel=mock(Channel.class);
        Message secondAttempt=new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties());
        secondAttempt.getMessageProperties().getHeaders().put("x-last-death-queue","q.rentas.retry.1");

        listener(dispatcher,rabbit).receive(secondAttempt,channel,41);
        verify(rabbit).send(eq("rentas.retry"),eq("q.rentas.retry.2"),any(Message.class),any(CorrelationData.class));
        verify(channel).basicAck(41,false);

        Message thirdAttempt=new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties());
        thirdAttempt.getMessageProperties().getHeaders().put("x-last-death-queue","q.rentas.retry.2");
        listener(dispatcher,rabbit).receive(thirdAttempt,channel,42);
        verify(rabbit).send(eq("rentas.retry"),eq("q.rentas.retry.3"),any(Message.class),any(CorrelationData.class));
        verify(channel).basicAck(42,false);

        Message fourthAttempt=new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties());
        fourthAttempt.getMessageProperties().getHeaders().put("x-last-death-queue","q.rentas.retry.3");
        listener(dispatcher,rabbit).receive(fourthAttempt,channel,44);
        verify(rabbit,times(1)).send(eq("rentas.retry"),eq("q.rentas.retry.3"),any(Message.class),any(CorrelationData.class));
        verify(channel).basicNack(44,false,false);
    }

    @Test void unconfirmedRetryIsNotAcknowledgedAsDelivered() throws Exception {
        String payload="{\"eventId\":\""+UUID.randomUUID()+"\",\"eventType\":\"ticketUpdated\",\"data\":{}}";
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        doThrow(new IllegalStateException("CORE_INBOUND_RETRY")).when(dispatcher).dispatch(payload);
        RabbitTemplate rejected=mock(RabbitTemplate.class);
        doAnswer(invocation->{CorrelationData correlation=invocation.getArgument(3);correlation.getFuture().complete(new CorrelationData.Confirm(false,"nack"));return null;})
            .when(rejected).send(any(String.class),any(String.class),any(Message.class),any(CorrelationData.class));
        Channel channel=mock(Channel.class);

        listener(dispatcher,rejected).receive(new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties()),channel,43);

        verify(channel).basicNack(43,false,false);
        verify(channel,never()).basicAck(43,false);
    }

    @Test void rejectedInboundLogsOnlySafeMetadataWithoutMessagesOrStackTraces() throws Exception {
        UUID eventId=UUID.randomUUID(),correlationId=UUID.randomUUID();
        String payload="{\"eventId\":\""+eventId+"\",\"correlationId\":\""+correlationId
            +"\",\"eventType\":\"ticketUpdated\",\"eventVersion\":\"1.0\",\"data\":{\"dni\":\"40111222\",\"cuit\":\"20-40111222-3\",\"token\":\"secret-token\"}}";
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        doThrow(new BusinessException("TAXPAYER_REFERENCE_NOT_FOUND","40111222 20-40111222-3 secret-token "+payload,422)).when(dispatcher).dispatch(payload);
        CoreRabbitMqConsumer listener=listener(dispatcher);
        Channel channel=mock(Channel.class);
        Logger logger=(Logger)LoggerFactory.getLogger(CoreRabbitMqConsumer.class);
        ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            listener.receive(new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties()),channel,31);
            verify(channel).basicNack(31,false,false);
            verify(channel,never()).basicNack(31,false,true);
            String output=logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",(left,right)->left+"\n"+right);
            assertThat(output).contains("eventId="+eventId,"correlationId="+correlationId,"eventType=ticketUpdated","eventVersion=1.0",
                    "reason=TAXPAYER_REFERENCE_NOT_FOUND","exceptionType=BusinessException")
                .doesNotContain("40111222","20-40111222-3","secret-token",payload);
            assertThat(logs.list).allSatisfy(event->assertThat(event.getThrowableProxy()).isNull());
            assertThat(logs.list).allSatisfy(event->assertThat(event.getTimeStamp()).isPositive());
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test void unknownEventVersionLogsStableReasonAndNacksWithoutDispatching() throws Exception {
        UUID eventId=UUID.randomUUID();
        String payload="""
            {"eventId":"%s","eventType":"ticketUpdated","eventVersion":"2.0","occurredAt":"2026-11-15T10:00:00-03:00","sourceModule":"atencion-ciudadana","data":{}}
            """.formatted(eventId);
        ConfirmedM1Consumer m1=mock(ConfirmedM1Consumer.class);ConfirmedM2Consumer m2=mock(ConfirmedM2Consumer.class);
        CoreM4Consumer m4=mock(CoreM4Consumer.class);ConfirmedM7Consumer m7=mock(ConfirmedM7Consumer.class);
        ConfirmedM8Consumer m8=mock(ConfirmedM8Consumer.class);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),m1,m2,m4,m7,m8);
        Channel channel=mock(Channel.class);RabbitTemplate rabbit=mock(RabbitTemplate.class);
        Logger logger=(Logger)LoggerFactory.getLogger(CoreRabbitMqConsumer.class);
        ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            listener(dispatcher,rabbit,"core-managed").receive(new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties()),channel,51);

            verify(channel).basicNack(51,false,false);verify(channel,never()).basicAck(51,false);
            verifyNoInteractions(rabbit,m1,m2,m4,m7,m8);
            assertInboundLogHasReason(logs,eventId,"UNSUPPORTED_EVENT_VERSION");
        } finally {logger.detachAppender(logs);}
    }

    @Test void m7TimestampWithoutOffsetLogsStablePayloadReasonBeforeConsumer() throws Exception {
        String resource="/contracts/inbound/M7_infractionConfirmed_REAL_SIN_OFFSET.json";
        String payload;
        try(var input=CoreRabbitMqConsumerTests.class.getResourceAsStream(resource)){
            assertThat(input).as("M7 contract fixture").isNotNull();
            payload=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        UUID eventId=UUID.fromString(new ObjectMapper().readTree(payload).path("eventId").asText());
        ConfirmedM7Consumer m7=mock(ConfirmedM7Consumer.class);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),
            mock(ConfirmedM1Consumer.class),mock(ConfirmedM2Consumer.class),mock(CoreM4Consumer.class),m7,mock(ConfirmedM8Consumer.class));
        Channel channel=mock(Channel.class);Logger logger=(Logger)LoggerFactory.getLogger(CoreRabbitMqConsumer.class);
        ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            listener(dispatcher,mock(RabbitTemplate.class),"core-managed").receive(
                new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties()),channel,52);

            verify(channel).basicNack(52,false,false);verify(channel,never()).basicAck(52,false);
            verifyNoInteractions(m7);
            assertInboundLogHasReason(logs,eventId,"INVALID_CORE_EVENT_PAYLOAD");
            assertThat(logs.list).noneSatisfy(event->assertThat(event.getFormattedMessage()).contains("14:14:29"));
        } finally {logger.detachAppender(logs);}
    }

    @Test void persistedM4UnsupportedCurrencyCodeIsLoggedWithoutChangingCoreManagedNack() throws Exception {
        UUID eventId=UUID.randomUUID();
        String payload="""
            {"eventId":"%s","eventType":"permitFeeGenerated","eventVersion":"1.0","occurredAt":"2026-11-15T10:00:00-03:00","sourceModule":"habilitaciones","data":{"id":"FEE-TEST","permitApplicationId":"%s","establishmentId":"%s","cuit":"30712345678","currency":"USD","amount":100}}
            """.formatted(eventId,UUID.randomUUID(),UUID.randomUUID());
        IntegrationEventLog failed=new IntegrationEventLog();failed.status=IntegrationEventStatus.FAILED;
        failed.errorMessage="M4_UNSUPPORTED_CURRENCY";
        CoreM4Consumer m4=mock(CoreM4Consumer.class);when(m4.consumePermitFee(any())).thenReturn(failed);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),
            mock(ConfirmedM1Consumer.class),mock(ConfirmedM2Consumer.class),m4,mock(ConfirmedM7Consumer.class),mock(ConfirmedM8Consumer.class));
        Channel channel=mock(Channel.class);RabbitTemplate rabbit=mock(RabbitTemplate.class);
        Logger logger=(Logger)LoggerFactory.getLogger(CoreRabbitMqConsumer.class);
        ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            listener(dispatcher,rabbit,"core-managed").receive(new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties()),channel,53);

            verify(channel).basicNack(53,false,false);verify(channel,never()).basicAck(53,false);
            verifyNoInteractions(rabbit);
            verify(m4).consumePermitFee(argThat(event->"USD".equals(event.data().currency())));
            assertInboundLogHasReason(logs,eventId,"M4_UNSUPPORTED_CURRENCY");
            assertThat(logs.list).noneSatisfy(event->assertThat(event.getFormattedMessage()).contains("30712345678","USD"));
        } finally {logger.detachAppender(logs);}
    }

    @Test void m4RetryReasonKeepsTheExistingLocalConfirmedRepublishAndAckPath() throws Exception {
        UUID eventId=UUID.randomUUID();
        String payload="""
            {"eventId":"%s","eventType":"permitFeeGenerated","eventVersion":"1.0","occurredAt":"2026-11-15T10:00:00-03:00","sourceModule":"habilitaciones","data":{"id":"FEE-TEST","permitApplicationId":"%s","establishmentId":"%s","cuit":"30712345678","currency":"USD","amount":100}}
            """.formatted(eventId,UUID.randomUUID(),UUID.randomUUID());
        IntegrationEventLog failed=new IntegrationEventLog();failed.status=IntegrationEventStatus.FAILED;
        failed.errorMessage="M4_UNSUPPORTED_CURRENCY";
        CoreM4Consumer m4=mock(CoreM4Consumer.class);when(m4.consumePermitFee(any())).thenReturn(failed);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),
            mock(ConfirmedM1Consumer.class),mock(ConfirmedM2Consumer.class),m4,mock(ConfirmedM7Consumer.class),mock(ConfirmedM8Consumer.class));
        RabbitTemplate rabbit=confirmedPublisher();Channel channel=mock(Channel.class);
        Message original=new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties());
        original.getMessageProperties().setMessageId(eventId.toString());

        listener(dispatcher,rabbit).receive(original,channel,54);

        verify(rabbit).send(eq("rentas.retry"),eq("q.rentas.retry.1"),argThat(retry->
            eventId.toString().equals(retry.getMessageProperties().getMessageId())
                &&payload.equals(new String(retry.getBody(),StandardCharsets.UTF_8))),any(CorrelationData.class));
        verify(channel).basicAck(54,false);verify(channel,never()).basicNack(eq(54),anyBoolean(),anyBoolean());
    }

    private void assertInboundLogHasReason(ListAppender<ILoggingEvent> logs,UUID eventId,String reason){
        assertThat(logs.list).singleElement().satisfies(event->{
            assertThat(event.getFormattedMessage()).contains("eventId="+eventId,"reason="+reason);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getTimeStamp()).isPositive();
        });
    }

    @Test void inboundLogOmitsUnsafeEventTypeValues() throws Exception {
        String payload="{\"eventType\":\"ticketUpdated\\nDNI=40111222\",\"data\":{}}";
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        doThrow(new IllegalArgumentException("unsafe input")).when(dispatcher).dispatch(payload);
        Logger logger=(Logger)LoggerFactory.getLogger(CoreRabbitMqConsumer.class);
        ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            listener(dispatcher).receive(
                new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties()),mock(Channel.class),32);
            String output=logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",String::concat);
            assertThat(output).contains("eventType=unavailable").doesNotContain("ticketUpdated","40111222");
        } finally {
            logger.detachAppender(logs);
        }
    }

    private CoreRabbitMqConsumer listener(CoreInboundEventDispatcher dispatcher){return listener(dispatcher,mock(RabbitTemplate.class),"local");}
    private CoreRabbitMqConsumer listener(CoreInboundEventDispatcher dispatcher,RabbitTemplate rabbit){return listener(dispatcher,rabbit,"local");}
    private CoreRabbitMqConsumer listener(CoreInboundEventDispatcher dispatcher,RabbitTemplate rabbit,String retryMode){return new CoreRabbitMqConsumer(dispatcher,rabbit,retryMode,"rentas.retry","q.rentas.retry.1","q.rentas.retry.2","q.rentas.retry.3",1000);}
    private RabbitTemplate confirmedPublisher(){RabbitTemplate rabbit=mock(RabbitTemplate.class);doAnswer(invocation->{CorrelationData correlation=invocation.getArgument(3);correlation.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(rabbit).send(any(String.class),any(String.class),any(Message.class),any(CorrelationData.class));return rabbit;}
    private Message message(long tag){MessageProperties properties=new MessageProperties();properties.setDeliveryTag(tag);return new Message(ticketUpdated().getBytes(StandardCharsets.UTF_8),properties);}
    private String envelope(String type,String source,String data){return """
        {"eventId":"%s","eventType":"%s","eventVersion":"1.0","occurredAt":"2026-10-30T10:00:00-03:00","sourceModule":"%s","correlationId":"%s","causationId":null,"data":%s}
        """.formatted(UUID.randomUUID(),type,source,UUID.randomUUID(),data);}
    private void acknowledge(CoreInboundEventDispatcher dispatcher,String payload,long tag) throws Exception {
        Channel channel=mock(Channel.class);
        listener(dispatcher).receive(new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties()),channel,tag);
        verify(channel).basicAck(tag,false);verify(channel,never()).basicNack(eq(tag),anyBoolean(),anyBoolean());
    }

    @Test void durableM7ErrorIsAcknowledgedForManualReconciliation() throws Exception {
        ConfirmedM7Consumer m7=mock(ConfirmedM7Consumer.class);
        ExternalObligation obligation=new ExternalObligation();obligation.status=ExternalObligationStatus.ERROR;
        when(m7.consume(any())).thenReturn(obligation);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),
            mock(ConfirmedM1Consumer.class),mock(ConfirmedM2Consumer.class),mock(CoreM4Consumer.class),m7,mock(ConfirmedM8Consumer.class));

        acknowledge(dispatcher,envelope("infractionConfirmed","transito","""
            {"infractionId":"%s","debtorId":"30111222","debtorIdType":"DNI","licensePlate":"AA123BB","infractionType":"SPEEDING","infractionDateTime":"2026-10-30T10:00:00-03:00","baseAmount":100,"aggravatingFactors":[],"finalAmount":100,"inspectorId":"INSPECTOR","location":{"street":"Principal","crossStreet":"Otra","latitude":-34.6,"longitude":-58.4}}
            """.formatted(UUID.randomUUID())),46);
    }
    private String ticketUpdated(){return """
        {"eventId":"%s","eventType":"ticketUpdated","eventVersion":"1.0","occurredAt":"%s","sourceModule":"atencion-ciudadana","correlationId":null,"causationId":null,"data":{"ticketId":"%s","publicId":"M2-100","citizenId":null,"isAnonymous":false,"responsibleAreaId":"M5","updateType":"STATUS_CHANGED","currentStatus":"IN_PROGRESS","currentPriority":"MEDIUM","progress":10,"updatedAt":"%s"}}
        """.formatted(UUID.randomUUID(),OffsetDateTime.now(),UUID.randomUUID(),OffsetDateTime.now());}
}
