package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

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
}
