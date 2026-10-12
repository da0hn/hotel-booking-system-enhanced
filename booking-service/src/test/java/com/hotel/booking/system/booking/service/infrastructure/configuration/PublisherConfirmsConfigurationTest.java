package com.hotel.booking.system.booking.service.infrastructure.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova que o {@code application.yml} real do serviço liga a garantia de entrega que o
 * {@code AbstractRabbitPublisher} pressupõe. Sem os três ajustes o publisher não tem como
 * saber que a mensagem foi roteada: o broker a descarta em silêncio quando não há fila.
 *
 * <p>O contexto sobe só com a autoconfiguração do RabbitMQ e não abre conexão: o
 * {@code CachingConnectionFactory} conecta de forma preguiçosa, então o teste dispensa
 * broker e Docker.</p>
 */
@DisplayName("Garantia de entrega no application.yml do serviço de reservas")
class PublisherConfirmsConfigurationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
    .withInitializer(context -> loadApplicationYml(context.getEnvironment()));

  @Test
  @DisplayName("ativa confirms correlacionados, returns e mensagens mandatory")
  void ativaConfirmsCorrelacionadosReturnsEMandatory() {
    this.runner.run(context -> {
      final var connectionFactory = context.getBean(CachingConnectionFactory.class);
      assertThat(connectionFactory.isPublisherConfirms()).isTrue();
      assertThat(connectionFactory.isSimplePublisherConfirms()).isFalse();
      assertThat(connectionFactory.isPublisherReturns()).isTrue();

      final var template = context.getBean(RabbitTemplate.class);
      assertThat(template.isMandatoryFor(new Message(new byte[0]))).isTrue();
    });
  }

  private static void loadApplicationYml(final ConfigurableEnvironment environment) {
    try {
      new YamlPropertySourceLoader()
        .load("application", new ClassPathResource("application.yml"))
        .forEach(environment.getPropertySources()::addLast);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
