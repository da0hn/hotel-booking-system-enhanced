package com.hotel.booking.system.payment.service.infrastructure.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guarda a convenção do projeto: todo listener consome com no mínimo {@value #MINIMUM_CONSUMERS}
 * threads.
 *
 * <p>Lê o {@code application.yml} de verdade, e não um valor montado no teste: o Boot ignora em
 * silêncio uma chave que não conhece, e um {@code concurrency} escrito no lugar errado deixaria
 * o serviço com o consumidor único que o Spring AMQP usa por padrão, sem nenhum aviso.</p>
 */
@DisplayName("Concorrência dos listeners do serviço de pagamentos")
class ListenerConcurrencyTest {

  private static final int MINIMUM_CONSUMERS = 3;

  @Test
  @DisplayName("consome com no mínimo 3 threads por listener")
  void consumesWithAtLeastThreeThreadsPerListener() throws IOException {
    final var environment = new StandardEnvironment();
    new YamlPropertySourceLoader()
      .load("application", new ClassPathResource("application.yml"))
      .forEach(environment.getPropertySources()::addLast);

    final var listener = Binder.get(environment).bind("spring.rabbitmq", RabbitProperties.class).get()
      .getListener().getSimple();

    assertThat(listener.getConcurrency())
      .as("spring.rabbitmq.listener.simple.concurrency")
      .isNotNull()
      .isGreaterThanOrEqualTo(MINIMUM_CONSUMERS);
  }

}
