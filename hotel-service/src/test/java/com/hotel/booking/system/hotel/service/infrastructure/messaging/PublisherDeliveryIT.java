package com.hotel.booking.system.hotel.service.infrastructure.messaging;

import com.hotel.booking.system.commons.core.application.exception.MessagePublishException;
import com.hotel.booking.system.commons.core.domain.event.TrustedEventPackages;
import com.hotel.booking.system.commons.infrastructure.messaging.AbstractRabbitPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prova contra um RabbitMQ de verdade o que os mocks do {@code AbstractRabbitPublisherTest}
 * só conseguem supor: que o broker responde ack, que a mensagem sem fila volta como return e
 * que um exchange inexistente ou um broker fora do ar chegam ao chamador como exceção.
 *
 * <p>O contexto sobe com a autoconfiguração do RabbitMQ e o {@code application.yml} real do
 * serviço. Assim o teste fecha a cadeia inteira — YAML, flags do template, comportamento —,
 * em vez de montar o template à mão com os mesmos valores e provar só a si mesmo.</p>
 *
 * <p>O container é estático e iniciado no bloco estático, como no {@code AbstractDatabaseIT}:
 * sobe uma vez por JVM e o encerramento fica com o Ryuk. Cada teste declara exchange e fila
 * com nome único, porque o broker é compartilhado entre eles.</p>
 */
@DisplayName("Entrega dos publishers contra um RabbitMQ real")
class PublisherDeliveryIT {

  private static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:3-management");

  static {
    BROKER.start();
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
    .withBean(MessageConverter.class, () -> new JacksonJsonMessageConverter(
      JsonMapper.builder().build(),
      TrustedEventPackages.names()
    ))
    .withInitializer(context -> loadApplicationYml(context.getEnvironment()));

  @Test
  @DisplayName("publica e entrega na fila quando o broker confirma e há fila vinculada")
  void publicaQuandoOBrokerConfirmaEHaFila() {
    this.onBroker().run(context -> {
      final var target = declareTopology(context.getBean(AmqpAdmin.class), true);
      final var template = context.getBean(RabbitTemplate.class);
      final var event = new PingEvent(UUID.randomUUID().toString());

      new PingPublisher(template, target.exchange(), target.routingKey()).publish(event);

      final var received = template.receive(target.queue(), 5_000);
      assertThat(received).isNotNull();
      assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).contains(event.id());
    });
  }

  @Test
  @DisplayName("falha quando nenhuma fila recebe a mensagem, em vez de o broker descartá-la")
  void falhaQuandoNenhumaFilaRecebeAMensagem() {
    this.onBroker().run(context -> {
      final var target = declareTopology(context.getBean(AmqpAdmin.class), false);
      final var publisher = new PingPublisher(
        context.getBean(RabbitTemplate.class),
        target.exchange(),
        target.routingKey()
      );

      assertThatThrownBy(() -> publisher.publish(new PingEvent("sem-fila")))
        .isInstanceOf(MessagePublishException.class)
        .hasMessageContaining("no queue received the message");
    });
  }

  @Test
  @DisplayName("falha quando o exchange não existe no broker")
  void falhaQuandoOExchangeNaoExiste() {
    this.onBroker().run(context -> {
      final var publisher = new PingPublisher(
        context.getBean(RabbitTemplate.class),
        "exchange.inexistente." + UUID.randomUUID(),
        "routing-key.qualquer"
      );

      assertThatThrownBy(() -> publisher.publish(new PingEvent("sem-exchange")))
        .isInstanceOf(MessagePublishException.class);
    });
  }

  @Test
  @DisplayName("falha preservando a causa quando o broker está inalcançável")
  void falhaQuandoOBrokerEstaInalcancavel() {
    this.runner
      .withPropertyValues("spring.rabbitmq.host=localhost", "spring.rabbitmq.port=1")
      .run(context -> {
        final var publisher = new PingPublisher(context.getBean(RabbitTemplate.class), "exchange.x", "routing-key.x");

        assertThatThrownBy(() -> publisher.publish(new PingEvent("broker-fora")))
          .isInstanceOf(MessagePublishException.class)
          .hasCauseInstanceOf(AmqpConnectException.class);
      });
  }

  private ApplicationContextRunner onBroker() {
    return this.runner.withPropertyValues(
      "spring.rabbitmq.host=" + BROKER.getHost(),
      "spring.rabbitmq.port=" + BROKER.getAmqpPort(),
      "spring.rabbitmq.username=" + BROKER.getAdminUsername(),
      "spring.rabbitmq.password=" + BROKER.getAdminPassword()
    );
  }

  private static Target declareTopology(final AmqpAdmin admin, final boolean bindQueue) {
    final var suffix = UUID.randomUUID().toString();
    final var target = new Target("exchange.it." + suffix, "queue.it." + suffix, "routing-key.it." + suffix);
    final var exchange = new DirectExchange(target.exchange());
    final var queue = new Queue(target.queue(), false, false, true);
    admin.declareExchange(exchange);
    admin.declareQueue(queue);
    if (bindQueue) {
      admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(target.routingKey()));
    }
    return target;
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

  private record Target(String exchange, String queue, String routingKey) {
  }

  private record PingEvent(String id) {
  }

  private static final class PingPublisher extends AbstractRabbitPublisher<PingEvent> {

    PingPublisher(final RabbitTemplate rabbitTemplate, final String exchange, final String routingKey) {
      super(rabbitTemplate, exchange, routingKey);
    }
  }
}
