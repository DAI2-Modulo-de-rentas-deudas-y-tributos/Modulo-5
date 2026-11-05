package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.domain.PageRequest;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BrokerAdapterConfigurationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PublisherConfiguration.class);

    @Test void localLogRemainsTheDefaultWhenBrokerAdapterIsUnset() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LocalLogEventPublisher.class);
            assertThat(context).doesNotHaveBean(RabbitMqEventPublisher.class);
            assertThat(context).doesNotHaveBean(CoreHttpEventPublisher.class);
            context.getBean(OutboxPublisher.class).publishPending();
            verify(context.getBean(OutboxRepository.class)).findPublishable(PageRequest.of(0, 50));
        });
    }

    @Test void brokerAdapterEnvironmentPropertySelectsCoreHttp() {
        UUID eventId = UUID.randomUUID();
        contextRunner.withPropertyValues(
            "BROKER_ADAPTER=core-http",
            "rentas.core.http.base-url=https://core.example",
            "rentas.core.http.module-secret=test-secret",
            "rentas.core.http.allowed-event-types=exemptionRequested",
            "rentas.core.http.allowed-event-ids=" + eventId)
            .run(context -> {
                assertThat(context).hasSingleBean(CoreHttpEventPublisher.class);
                assertThat(context).doesNotHaveBean(LocalLogEventPublisher.class);
                assertThat(context).doesNotHaveBean(RabbitMqEventPublisher.class);
                context.getBean(OutboxPublisher.class).publishPending();
                verify(context.getBean(OutboxRepository.class)).findPublishableForCoreHttp(
                    Set.of("exemptionRequested"), Set.of(eventId), PageRequest.of(0, 1));
            });
    }

    @Test void brokerAdapterEnvironmentPropertySelectsRabbitMq() {
        contextRunner.withPropertyValues(
            "BROKER_ADAPTER=rabbitmq",
            "spring.rabbitmq.username=test-user",
            "spring.rabbitmq.password=test-password")
            .run(context -> {
                assertThat(context).hasSingleBean(RabbitMqEventPublisher.class);
                assertThat(context).doesNotHaveBean(LocalLogEventPublisher.class);
                assertThat(context).doesNotHaveBean(CoreHttpEventPublisher.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({LocalLogEventPublisher.class, RabbitMqEventPublisher.class, CoreHttpEventPublisher.class, OutboxPublisher.class})
    static class PublisherConfiguration {
        @Bean static ConversionService conversionService() { return ApplicationConversionService.getSharedInstance(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean RabbitTemplate rabbitTemplate() { return mock(RabbitTemplate.class); }
        @Bean OutboxRepository outboxRepository() { return mock(OutboxRepository.class); }
    }
}
