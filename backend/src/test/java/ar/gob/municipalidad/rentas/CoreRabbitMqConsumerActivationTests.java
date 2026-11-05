package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CoreRabbitMqConsumerActivationTests {
    private final ApplicationContextRunner contextRunner=new ApplicationContextRunner()
        .withUserConfiguration(ConsumerConfiguration.class);

    @Test void coreHttpCanReceiveRabbitEventsOnlyWhenExplicitlyEnabled() {
        contextRunner.withPropertyValues("rentas.broker-adapter=core-http","rentas.core.amqp.consumer-enabled=true")
            .run(context->assertThat(context).hasSingleBean(CoreRabbitMqConsumer.class));
    }

    @Test void consumerRemainsDisabledByDefaultForEveryOutboundTransport() {
        for(String transport:new String[]{"local-log","core-http","rabbitmq"}) {
            contextRunner.withPropertyValues("rentas.broker-adapter="+transport)
                .run(context->assertThat(context).doesNotHaveBean(CoreRabbitMqConsumer.class));
        }
    }

    @Configuration(proxyBeanMethods=false)
    @Import(CoreRabbitMqConsumer.class)
    static class ConsumerConfiguration {
        @Bean CoreInboundEventDispatcher dispatcher(){return mock(CoreInboundEventDispatcher.class);}
        @Bean RabbitTemplate rabbitTemplate(){return mock(RabbitTemplate.class);}
    }
}
