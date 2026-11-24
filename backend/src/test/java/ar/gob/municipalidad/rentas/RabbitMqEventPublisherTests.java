package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RabbitMqEventPublisherTests {
    private static final String EXCHANGE="muni.inbox";
    private static final String QUEUE="q.rentas.test";
    private static final GenericContainer<?> RABBIT=new GenericContainer<>(DockerImageName.parse("rabbitmq:4.3-management"))
        .withEnv("RABBITMQ_DEFAULT_USER","m5-test")
        .withEnv("RABBITMQ_DEFAULT_PASS","m5-test-only")
        .withExposedPorts(5672)
        .waitingFor(Wait.forLogMessage(".*Server startup complete.*",1));
    private static CachingConnectionFactory connections;
    private static RabbitTemplate template;
    private static RabbitAdmin admin;
    private static RabbitMqEventPublisher publisher;

    @BeforeAll static void startRabbit() throws Exception {
        if(!DockerClientFactory.instance().isDockerAvailable())throw new IllegalStateException("Docker is required for the isolated RabbitMQ contract test");
        RABBIT.start();
        assertThat(RABBIT.execInContainer("rabbitmqctl","add_user","m5-other","m5-other-only").getExitCode()).isZero();
        assertThat(RABBIT.execInContainer("rabbitmqctl","set_permissions","-p","/","m5-other",".*",".*",".*").getExitCode()).isZero();
        connections=new CachingConnectionFactory(RABBIT.getHost(),RABBIT.getMappedPort(5672));
        connections.setUsername("m5-test");connections.setPassword("m5-test-only");
        connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connections.setPublisherReturns(true);
        template=new RabbitTemplate(connections);template.setMandatory(true);
        admin=new RabbitAdmin(connections);
        admin.declareExchange(new FanoutExchange(EXCHANGE,true,false));
        Queue testQueue=new Queue(QUEUE,true,false,false);
        admin.declareQueue(testQueue);admin.declareBinding(BindingBuilder.bind(testQueue).to(new FanoutExchange(EXCHANGE,true,false)));
        publisher=new RabbitMqEventPublisher(template,new ObjectMapper(),EXCHANGE,Duration.ofSeconds(5),"m5-test","m5-test-only");
    }

    @AfterAll static void stopRabbit() {
        if(connections!=null)connections.destroy();
        if(RABBIT.isRunning())RABBIT.stop();
    }

    @Test void publisherWaitsForBrokerConfirmAndPreservesPersistentEnvelope() {
        String id=UUID.randomUUID().toString();
        String payload="""
            {"eventId":"%s","eventType":"paymentRegistered","eventVersion":"1.0","occurredAt":"%s","sourceModule":"rentas","correlationId":null,"causationId":null,"user_id":"forged-user","data":{"paymentId":"%s"}}
            """.formatted(id,OffsetDateTime.now().toString(),UUID.randomUUID());

        assertThat(publisher.publish("paymentRegistered","CORE",payload)).isEqualTo(OutboxStatus.BROKER_CONFIRMED);
        Message received=null;
        for(int attempt=0;attempt<20&&received==null;attempt++){
            received=template.receive(QUEUE,250);
        }
        assertThat(received).isNotNull();
        assertThat(new String(received.getBody(),StandardCharsets.UTF_8)).isEqualTo(payload);
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(id);
        assertThat(received.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(received.getMessageProperties().getReceivedUserId()).isEqualTo("m5-test");
        assertThat(received.getMessageProperties().getHeaders()).doesNotContainKeys("userId","user_id");
    }

    @Test void interleavedPublishersUseTheirOwnConnectionIdentity() throws Exception {
        String exchange="muni.inbox.identity."+UUID.randomUUID();
        String queue="q.rentas.identity."+UUID.randomUUID();
        FanoutExchange isolatedExchange=new FanoutExchange(exchange,true,false);
        admin.declareExchange(isolatedExchange);
        admin.declareQueue(new Queue(queue,true,false,false));
        admin.declareBinding(BindingBuilder.bind(new Queue(queue,true,false,false)).to(isolatedExchange));

        CachingConnectionFactory otherConnections=new CachingConnectionFactory(RABBIT.getHost(),RABBIT.getMappedPort(5672));
        otherConnections.setUsername("m5-other");otherConnections.setPassword("m5-other-only");
        otherConnections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        RabbitTemplate otherTemplate=new RabbitTemplate(otherConnections);otherTemplate.setMandatory(true);
        try {
            RabbitMqEventPublisher current=new RabbitMqEventPublisher(template,new ObjectMapper(),exchange,Duration.ofSeconds(5),"m5-test","m5-test-only");
            RabbitMqEventPublisher other=new RabbitMqEventPublisher(otherTemplate,new ObjectMapper(),exchange,Duration.ofSeconds(5),"m5-other","m5-other-only");
            assertThat(current.publish("paymentRegistered","CORE",payload())).isEqualTo(OutboxStatus.BROKER_CONFIRMED);
            assertThat(other.publish("paymentRegistered","CORE",payload())).isEqualTo(OutboxStatus.BROKER_CONFIRMED);
            assertThat(current.publish("paymentRegistered","CORE",payload())).isEqualTo(OutboxStatus.BROKER_CONFIRMED);

            assertThat(template.receive(queue,3000).getMessageProperties().getReceivedUserId()).isEqualTo("m5-test");
            assertThat(template.receive(queue,3000).getMessageProperties().getReceivedUserId()).isEqualTo("m5-other");
            assertThat(template.receive(queue,3000).getMessageProperties().getReceivedUserId()).isEqualTo("m5-test");
        } finally {
            otherConnections.destroy();
        }
    }

    @Test void rabbitRejectsUserIdThatDoesNotMatchAuthenticatedConnection() throws Exception {
        com.rabbitmq.client.ConnectionFactory factory=new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(RABBIT.getHost());factory.setPort(RABBIT.getMappedPort(5672));
        factory.setUsername("m5-test");factory.setPassword("m5-test-only");
        try(var connection=factory.newConnection()) {
            Channel channel=connection.createChannel();
            channel.basicPublish(EXCHANGE,"",new AMQP.BasicProperties.Builder().userId("forged-user").build(),payload().getBytes(StandardCharsets.UTF_8));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(channel.isOpen()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(channel.isOpen()).isFalse();
            assertThat(channel.getCloseReason()).isNotNull();
            assertThat(channel.getCloseReason().getReason().toString()).contains("406").contains("authenticated user");
        }
    }

    @Test void exactM2RoutedFixtureTravelsThroughIsolatedRabbitAndTheRealDispatcher() throws Exception {
        String payload;
        try(var input=getClass().getResourceAsStream("/contracts/inbound/M2_ticketUpdated_ROUTED_REAL.json")){
            if(input==null)throw new IllegalArgumentException("Missing exact M2 test fixture");
            payload=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        String suffix=UUID.randomUUID().toString().replace("-","");
        String exchangeName=EXCHANGE+".m2."+suffix,queueName=QUEUE+".m2."+suffix;
        FanoutExchange exchange=new FanoutExchange(exchangeName,true,false);
        admin.declareExchange(exchange);
        Queue queue=new Queue(queueName,true,false,true);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange));
        org.springframework.amqp.core.MessageProperties properties=new org.springframework.amqp.core.MessageProperties();
        properties.setMessageId("e06a8885-60b8-4660-83ff-6feb23843c26");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        template.send(exchangeName,"",new Message(payload.getBytes(StandardCharsets.UTF_8),properties));

        Message received=template.receive(queueName,3000);
        assertThat(received).isNotNull();
        assertThat(new String(received.getBody(),StandardCharsets.UTF_8)).isEqualTo(payload);
        ConfirmedM2Consumer m2=mock(ConfirmedM2Consumer.class);
        CoreInboundEventDispatcher dispatcher=new CoreInboundEventDispatcher(new ObjectMapper().findAndRegisterModules(),
            mock(ConfirmedM1Consumer.class),m2,mock(CoreM4Consumer.class),mock(ConfirmedM7Consumer.class),mock(ConfirmedM8Consumer.class));

        dispatcher.dispatch(new String(received.getBody(),StandardCharsets.UTF_8));

        org.mockito.ArgumentCaptor<ConfirmedInboundEvents.M2TicketUpdatedEvent> event=
            org.mockito.ArgumentCaptor.forClass(ConfirmedInboundEvents.M2TicketUpdatedEvent.class);
        verify(m2).consume(event.capture());
        assertThat(event.getValue().eventId()).isEqualTo(UUID.fromString("e06a8885-60b8-4660-83ff-6feb23843c26"));
        assertThat(event.getValue().correlationId()).isEqualTo(UUID.fromString("b766e401-e818-4c42-ae63-eb3aa6b3c68f"));
        assertThat(event.getValue().data().ticketId()).isEqualTo(UUID.fromString("1ab9e286-2ac9-40d5-b46e-e78c414f535a"));
        assertThat(event.getValue().data().publicId()).isEqualTo("TK-2026-000047");
        assertThat(event.getValue().data().updateType()).isEqualTo("ROUTED");
        assertThat(event.getValue().data().currentStatus()).isEqualTo("ROUTED");
        assertThat(event.getValue().data().responsibleAreaId()).isEqualTo("M5");
    }

    @Test void transientInboundFailuresRetryThreeTimesThenDeadLetterWithTheSameEventId() throws Exception {
        RetryTopology topology=declareRetryTopology();
        String eventId=UUID.randomUUID().toString();
        String payload="{\"eventId\":\""+eventId+"\",\"eventType\":\"ticketUpdated\",\"eventVersion\":\"1.0\",\"data\":{}}";
        Message original=new Message(payload.getBytes(StandardCharsets.UTF_8),new org.springframework.amqp.core.MessageProperties());
        original.getMessageProperties().setMessageId(eventId);
        original.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        original.getMessageProperties().setHeader("x-core-event-id",eventId);
        original.getMessageProperties().setHeader("x-core-target-module","rentas");
        original.getMessageProperties().setHeader("x-core-attempt",1);
        original.getMessageProperties().setHeader("x-core-trace-id","trace-local-test");
        CorrelationData publish=new CorrelationData(eventId);
        template.send("",topology.mainQueue(),original,publish);
        assertThat(publish.getFuture().get(5,TimeUnit.SECONDS).isAck()).isTrue();

        AtomicInteger attempts=new AtomicInteger();
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        doAnswer(invocation->{attempts.incrementAndGet();throw new IllegalStateException("CORE_INBOUND_RETRY");})
            .when(dispatcher).dispatch(payload);
        CoreRabbitMqConsumer listener=new CoreRabbitMqConsumer(dispatcher,template,"local",topology.retryExchange(),topology.retryQueue1(),topology.retryQueue2(),topology.retryQueue3(),5000);

        try(var connection=connections.createConnection();Channel channel=connection.createChannel(false)){
            for(int attempt=0;attempt<4;attempt++){
                GetResponse delivery=awaitGet(channel,topology.mainQueue(),8000);
                assertThat(delivery).as("entrega %d de cuatro",attempt+1).isNotNull();
                Object lastDeathQueue=delivery.getProps().getHeaders().get("x-last-death-queue");
                assertThat(lastDeathQueue==null?null:lastDeathQueue.toString())
                    .isEqualTo(attempt==0?null:attempt==1?topology.retryQueue1():attempt==2?topology.retryQueue2():topology.retryQueue3());
                assertThat(delivery.getProps().getMessageId()).isEqualTo(eventId);
                if(attempt>0)assertThat(delivery.getProps().getUserId()).isEqualTo("m5-test");
                assertThat(delivery.getProps().getHeaders().get("x-core-event-id").toString()).isEqualTo(eventId);
                assertThat(delivery.getProps().getHeaders().get("x-core-target-module").toString()).isEqualTo("rentas");
                assertThat(delivery.getProps().getHeaders().get("x-core-trace-id").toString()).isEqualTo("trace-local-test");
                assertThat(new String(delivery.getBody(),StandardCharsets.UTF_8)).isEqualTo(payload);
                Message inbound=asSpringMessage(delivery);
                if(attempt==0)inbound.getMessageProperties().setUserId("foreign-publisher");
                listener.receive(inbound,channel,delivery.getEnvelope().getDeliveryTag());
            }
            GetResponse deadLetter=awaitGet(channel,topology.deadLetterQueue(),2000);
            assertThat(deadLetter).isNotNull();
            assertThat(deadLetter.getProps().getMessageId()).isEqualTo(eventId);
            assertThat(new String(deadLetter.getBody(),StandardCharsets.UTF_8)).contains(eventId);
            channel.basicAck(deadLetter.getEnvelope().getDeliveryTag(),false);
        }
        assertThat(attempts).hasValue(4);
    }

    @Test void coreManagedModeNacksToBrokerDlxWithoutPublishingToLocalRetryQueues() throws Exception {
        RetryTopology topology=declareRetryTopology();
        String eventId=UUID.randomUUID().toString();
        String payload="{\"eventId\":\""+eventId+"\",\"eventType\":\"ticketUpdated\",\"eventVersion\":\"1.0\",\"data\":{}}";
        Message original=new Message(payload.getBytes(StandardCharsets.UTF_8),new org.springframework.amqp.core.MessageProperties());
        original.getMessageProperties().setMessageId(eventId);
        template.send("",topology.mainQueue(),original,new CorrelationData(eventId));
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        doThrow(new IllegalStateException("CORE_INBOUND_RETRY")).when(dispatcher).dispatch(payload);
        CoreRabbitMqConsumer listener=new CoreRabbitMqConsumer(dispatcher,template,"core-managed",topology.retryExchange(),
            topology.retryQueue1(),topology.retryQueue2(),topology.retryQueue3(),5000);

        try(var connection=connections.createConnection();Channel channel=connection.createChannel(false)){
            GetResponse delivery=awaitGet(channel,topology.mainQueue(),2000);
            assertThat(delivery).isNotNull();
            listener.receive(asSpringMessage(delivery),channel,delivery.getEnvelope().getDeliveryTag());
            GetResponse deadLetter=awaitGet(channel,topology.deadLetterQueue(),2000);
            assertThat(deadLetter).isNotNull();
            assertThat(deadLetter.getProps().getMessageId()).isEqualTo(eventId);
            assertThat(deadLetter.getProps().getHeaders()).containsKey("x-death");
            assertThat(awaitGet(channel,topology.retryQueue1(),200)).isNull();
            assertThat(awaitGet(channel,topology.retryQueue2(),200)).isNull();
            assertThat(awaitGet(channel,topology.retryQueue3(),200)).isNull();
            channel.basicAck(deadLetter.getEnvelope().getDeliveryTag(),false);
        }
        verify(dispatcher,times(1)).dispatch(payload);
    }

    @Test void permanentInboundFailureGoesDirectlyToDeadLetterWithoutRetry() throws Exception {
        RetryTopology topology=declareRetryTopology();
        String eventId=UUID.randomUUID().toString();
        String payload="{\"eventId\":\""+eventId+"\",\"eventType\":\"unknown\",\"eventVersion\":\"1.0\",\"data\":{}}";
        Message original=new Message(payload.getBytes(StandardCharsets.UTF_8),new org.springframework.amqp.core.MessageProperties());
        original.getMessageProperties().setMessageId(eventId);
        template.send("",topology.mainQueue(),original,new CorrelationData(eventId));
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        doThrow(new IllegalArgumentException("UNSUPPORTED_CORE_EVENT_TYPE")).when(dispatcher).dispatch(payload);
        CoreRabbitMqConsumer listener=new CoreRabbitMqConsumer(dispatcher,template,"local",topology.retryExchange(),topology.retryQueue1(),topology.retryQueue2(),topology.retryQueue3(),5000);

        try(var connection=connections.createConnection();Channel channel=connection.createChannel(false)){
            GetResponse delivery=awaitGet(channel,topology.mainQueue(),2000);
            assertThat(delivery).isNotNull();
            listener.receive(asSpringMessage(delivery),channel,delivery.getEnvelope().getDeliveryTag());
            GetResponse deadLetter=awaitGet(channel,topology.deadLetterQueue(),2000);
            assertThat(deadLetter).isNotNull();
            assertThat(deadLetter.getProps().getMessageId()).isEqualTo(eventId);
            assertThat(new String(deadLetter.getBody(),StandardCharsets.UTF_8)).contains(eventId);
        }
        verify(dispatcher,times(1)).dispatch(payload);
    }

    @Test void invalidEnvelopeIsRejectedBeforePublishing() {
        assertThatThrownBy(()->publisher.publish("event","CORE","{\"eventId\":\"not-a-uuid\"}"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_CORE_ENVELOPE");
    }

    @Test void rejectsMissingCredentialsWhenRabbitAdapterIsConfigured() {
        assertThatThrownBy(()->new RabbitMqEventPublisher(template,new ObjectMapper(),EXCHANGE,Duration.ofSeconds(5),"",""))
            .isInstanceOf(IllegalStateException.class).hasMessage("CORE_RABBITMQ_CREDENTIALS_REQUIRED");
    }

    @Test void rejectsConfiguredUsernameThatDiffersFromConnectionFactoryIdentity() {
        assertThatThrownBy(()->new RabbitMqEventPublisher(template,new ObjectMapper(),EXCHANGE,Duration.ofSeconds(5),"forged-user","m5-test-only"))
            .isInstanceOf(IllegalStateException.class).hasMessage("CORE_RABBITMQ_CREDENTIALS_MISMATCH");
    }

    @Test void negativePublisherConfirmFailsWithoutClaimingBrokerAcceptance() {
        RabbitTemplate rejected=mock(RabbitTemplate.class);
        org.mockito.Mockito.when(rejected.getConnectionFactory()).thenReturn(connections);
        doAnswer(invocation->{
            CorrelationData correlation=invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(false,"nack"));
            return null;
        }).when(rejected).convertAndSend(any(String.class),any(String.class),any(Object.class),any(org.springframework.amqp.core.MessagePostProcessor.class),any(CorrelationData.class));
        RabbitMqEventPublisher rejectingPublisher=new RabbitMqEventPublisher(rejected,new ObjectMapper(),EXCHANGE,Duration.ofSeconds(1),"m5-test","m5-test-only");

        assertThatThrownBy(()->rejectingPublisher.publish("paymentRegistered","CORE",payload()))
            .isInstanceOf(IllegalStateException.class).hasMessage("CORE_BROKER_CONFIRM_REJECTED");
    }

    @Test void confirmTimeoutFailsClosed() {
        RabbitTemplate silent=mock(RabbitTemplate.class);
        org.mockito.Mockito.when(silent.getConnectionFactory()).thenReturn(connections);
        RabbitMqEventPublisher timingOutPublisher=new RabbitMqEventPublisher(silent,new ObjectMapper(),EXCHANGE,Duration.ofMillis(1),"m5-test","m5-test-only");

        assertThatThrownBy(()->timingOutPublisher.publish("paymentRegistered","CORE",payload()))
            .isInstanceOf(IllegalStateException.class).hasMessage("CORE_BROKER_CONFIRM_FAILED");
    }

    @Test void mandatoryReturnRejectsUnroutableMessage() {
        String exchange="m5.unroutable."+UUID.randomUUID();
        admin.declareExchange(new DirectExchange(exchange,true,false));
        RabbitMqEventPublisher unroutablePublisher=new RabbitMqEventPublisher(template,new ObjectMapper(),exchange,Duration.ofSeconds(5),"m5-test","m5-test-only");

        assertThatThrownBy(()->unroutablePublisher.publish("paymentRegistered","CORE",payload()))
            .isInstanceOf(IllegalStateException.class).hasMessage("CORE_BROKER_CONFIRM_REJECTED");
    }

    @Test void publisherRecoversAfterBrokerEndpointReturnsAndRetriesSameEnvelope() throws Exception {
        String brokerAddress=RABBIT.getHost()+":"+RABBIT.getMappedPort(5672);
        String payload=payload();
        try {
            connections.setAddresses("127.0.0.1:1");
            connections.resetConnection();
            assertThatThrownBy(()->publisher.publish("paymentRegistered","CORE",payload))
                .isInstanceOf(RuntimeException.class);

            connections.setAddresses(brokerAddress);
            connections.resetConnection();
            assertThat(publisher.publish("paymentRegistered","CORE",payload)).isEqualTo(OutboxStatus.BROKER_CONFIRMED);
            Message received=null;
            for(int attempt=0;attempt<20&&received==null;attempt++)received=template.receive(QUEUE,250);
            assertThat(received).isNotNull();
            assertThat(new String(received.getBody(),StandardCharsets.UTF_8)).isEqualTo(payload);
            assertThat(received.getMessageProperties().getMessageId()).isEqualTo(new ObjectMapper().readTree(payload).path("eventId").asText());
        } finally {
            connections.setAddresses(brokerAddress);
            connections.resetConnection();
        }
    }

    private String payload(){
        String id=UUID.randomUUID().toString();
        return """
            {"eventId":"%s","eventType":"paymentRegistered","eventVersion":"1.0","occurredAt":"%s","sourceModule":"rentas","correlationId":null,"causationId":null,"data":{"paymentId":"%s"}}
            """.formatted(id,OffsetDateTime.now(),UUID.randomUUID());
    }

    private RetryTopology declareRetryTopology(){
        String suffix=UUID.randomUUID().toString().replace("-","");
        String main="q.rentas.retry-test."+suffix,dlx="rentas.retry-test.dlx."+suffix,dlq=main+".dlq";
        String returned="rentas.retry-test.return."+suffix,retry="rentas.retry-test.exchange."+suffix;
        String retry1=main+".retry.1",retry2=main+".retry.2",retry3=main+".retry.3";
        DirectExchange returnExchange=new DirectExchange(returned,true,false),retryExchange=new DirectExchange(retry,true,false);
        FanoutExchange deadLetterExchange=new FanoutExchange(dlx,true,false);
        admin.declareExchange(returnExchange);admin.declareExchange(retryExchange);admin.declareExchange(deadLetterExchange);
        Queue mainQueue=QueueBuilder.durable(main).deadLetterExchange(dlx).build();
        Queue deadLetterQueue=QueueBuilder.durable(dlq).build();
        Queue firstRetry=QueueBuilder.durable(retry1).ttl(150).deadLetterExchange(returned).deadLetterRoutingKey(main).build();
        Queue secondRetry=QueueBuilder.durable(retry2).ttl(250).deadLetterExchange(returned).deadLetterRoutingKey(main).build();
        Queue thirdRetry=QueueBuilder.durable(retry3).ttl(350).deadLetterExchange(returned).deadLetterRoutingKey(main).build();
        admin.declareQueue(mainQueue);admin.declareQueue(deadLetterQueue);admin.declareQueue(firstRetry);admin.declareQueue(secondRetry);admin.declareQueue(thirdRetry);
        admin.declareBinding(BindingBuilder.bind(mainQueue).to(returnExchange).with(main));
        admin.declareBinding(BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange));
        admin.declareBinding(BindingBuilder.bind(firstRetry).to(retryExchange).with(retry1));
        admin.declareBinding(BindingBuilder.bind(secondRetry).to(retryExchange).with(retry2));
        admin.declareBinding(BindingBuilder.bind(thirdRetry).to(retryExchange).with(retry3));
        return new RetryTopology(main,retry,retry1,retry2,retry3,dlq);
    }

    private GetResponse awaitGet(Channel channel,String queue,long timeoutMs)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        GetResponse response;
        while((response=channel.basicGet(queue,false))==null&&System.nanoTime()<deadline)Thread.sleep(20);
        return response;
    }

    private Message asSpringMessage(GetResponse delivery){
        var properties=new org.springframework.amqp.core.MessageProperties();
        Map<String,Object> headers=delivery.getProps().getHeaders();
        if(headers!=null)properties.setHeaders(new HashMap<>(headers));
        properties.setMessageId(delivery.getProps().getMessageId());
        properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
        properties.setRedelivered(delivery.getEnvelope().isRedeliver());
        return new Message(delivery.getBody(),properties);
    }

    private record RetryTopology(String mainQueue,String retryExchange,String retryQueue1,String retryQueue2,String retryQueue3,String deadLetterQueue){}
}
