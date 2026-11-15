package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static RabbitMqEventPublisher publisher;

    @BeforeAll static void startRabbit() {
        if(!DockerClientFactory.instance().isDockerAvailable())throw new IllegalStateException("Docker is required for the isolated RabbitMQ contract test");
        RABBIT.start();
        connections=new CachingConnectionFactory(RABBIT.getHost(),RABBIT.getMappedPort(5672));
        connections.setUsername("m5-test");connections.setPassword("m5-test-only");
        connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connections.setPublisherReturns(true);
        template=new RabbitTemplate(connections);template.setMandatory(true);
        RabbitAdmin admin=new RabbitAdmin(connections);
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
            {"eventId":"%s","eventType":"paymentRegistered","eventVersion":"1.0","occurredAt":"%s","sourceModule":"rentas","correlationId":null,"causationId":null,"data":{"paymentId":"%s"}}
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
    }

    @Test void transientInboundFailuresRetryThreeTimesThenDeadLetterWithTheSameEventId() throws Exception {
        RetryTopology topology=declareRetryTopology();
        String eventId=UUID.randomUUID().toString();
        String payload="{\"eventId\":\""+eventId+"\",\"eventType\":\"ticketUpdated\",\"eventVersion\":\"1.0\",\"data\":{}}";
        Message original=new Message(payload.getBytes(StandardCharsets.UTF_8),new org.springframework.amqp.core.MessageProperties());
        original.getMessageProperties().setMessageId(eventId);
        original.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        CorrelationData publish=new CorrelationData(eventId);
        template.send("",topology.mainQueue(),original,publish);
        assertThat(publish.getFuture().get(5,TimeUnit.SECONDS).isAck()).isTrue();

        AtomicInteger attempts=new AtomicInteger();
        CoreInboundEventDispatcher dispatcher=mock(CoreInboundEventDispatcher.class);
        doAnswer(invocation->{attempts.incrementAndGet();throw new IllegalStateException("CORE_INBOUND_RETRY");})
            .when(dispatcher).dispatch(payload);
        CoreRabbitMqConsumer listener=new CoreRabbitMqConsumer(dispatcher,template,topology.retryExchange(),topology.retryQueue1(),topology.retryQueue2(),topology.retryQueue3(),5000);

        try(var connection=connections.createConnection();Channel channel=connection.createChannel(false)){
            for(int attempt=0;attempt<4;attempt++){
                GetResponse delivery=awaitGet(channel,topology.mainQueue(),8000);
                assertThat(delivery).as("entrega %d de cuatro",attempt+1).isNotNull();
                Object lastDeathQueue=delivery.getProps().getHeaders().get("x-last-death-queue");
                assertThat(lastDeathQueue==null?null:lastDeathQueue.toString())
                    .isEqualTo(attempt==0?null:attempt==1?topology.retryQueue1():attempt==2?topology.retryQueue2():topology.retryQueue3());
                assertThat(delivery.getProps().getMessageId()).isEqualTo(eventId);
                assertThat(new String(delivery.getBody(),StandardCharsets.UTF_8)).isEqualTo(payload);
                listener.receive(asSpringMessage(delivery),channel,delivery.getEnvelope().getDeliveryTag());
            }
            GetResponse deadLetter=awaitGet(channel,topology.deadLetterQueue(),2000);
            assertThat(deadLetter).isNotNull();
            assertThat(deadLetter.getProps().getMessageId()).isEqualTo(eventId);
            assertThat(new String(deadLetter.getBody(),StandardCharsets.UTF_8)).contains(eventId);
            channel.basicAck(deadLetter.getEnvelope().getDeliveryTag(),false);
        }
        assertThat(attempts).hasValue(4);
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
        CoreRabbitMqConsumer listener=new CoreRabbitMqConsumer(dispatcher,template,topology.retryExchange(),topology.retryQueue1(),topology.retryQueue2(),topology.retryQueue3(),5000);

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

    @Test void negativePublisherConfirmFailsWithoutClaimingBrokerAcceptance() {
        RabbitTemplate rejected=mock(RabbitTemplate.class);
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
        RabbitMqEventPublisher timingOutPublisher=new RabbitMqEventPublisher(silent,new ObjectMapper(),EXCHANGE,Duration.ofMillis(1),"m5-test","m5-test-only");

        assertThatThrownBy(()->timingOutPublisher.publish("paymentRegistered","CORE",payload()))
            .isInstanceOf(IllegalStateException.class).hasMessage("CORE_BROKER_CONFIRM_FAILED");
    }

    @Test void mandatoryReturnRejectsUnroutableMessage() {
        String exchange="m5.unroutable."+UUID.randomUUID();
        new RabbitAdmin(connections).declareExchange(new DirectExchange(exchange,true,false));
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
        RabbitAdmin admin=new RabbitAdmin(connections);
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
