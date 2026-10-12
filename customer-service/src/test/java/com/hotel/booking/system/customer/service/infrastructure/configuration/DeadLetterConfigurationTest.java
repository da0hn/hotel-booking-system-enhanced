package com.hotel.booking.system.customer.service.infrastructure.configuration;

import com.hotel.booking.system.commons.infrastructure.messaging.DeadLetterTopology;
import com.hotel.booking.system.commons.infrastructure.messaging.EventMessageConverter;
import com.hotel.booking.system.customer.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.customer.service.infrastructure.messaging.properties.QueueProperties;
import com.hotel.booking.system.customer.service.infrastructure.messaging.properties.RoutingKeyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Dead-letter e retry da mensageria do serviço de clientes")
class DeadLetterConfigurationTest {

  private static final RoutingKeyProperties ROUTING_KEYS = new RoutingKeyProperties("r1");

  private static final QueueProperties QUEUES = new QueueProperties("q1");

  private static final ExchangeProperties EXCHANGES = new ExchangeProperties("customer");

  private final RabbitMQConfiguration config = new RabbitMQConfiguration(ROUTING_KEYS, QUEUES, EXCHANGES);

  @Test
  @DisplayName("aponta a fila de negócio para a DLQ dela")
  void apontaAFilaParaASuaDlq() {
    assertThat(this.config.customerBookingUpdateQueue().getArguments())
      .containsEntry("x-dead-letter-exchange", DeadLetterTopology.EXCHANGE)
      .containsEntry("x-dead-letter-routing-key", "q1.dlq");
  }

  @Test
  @DisplayName("declara a DLQ da fila")
  void declaraADlqDaFila() {
    assertThat(this.config.deadLetterDeclarables().getDeclarablesByType(Queue.class))
      .extracting(Queue::getName)
      .containsExactly("q1.dlq");
  }

  @Test
  @DisplayName("usa o conversor de eventos do commons")
  void usaOConversorDeEventosDoCommons() {
    assertThat(this.config.jsonMessageConverter(new JsonMapperConfiguration().jsonMapper()))
      .isInstanceOf(EventMessageConverter.class);
  }

  @Test
  @DisplayName("registra a DLQ, o recoverer e o classificador de retry no contexto")
  void registraDlqRecovererEClassificadorNoContexto() {
    new ApplicationContextRunner()
      .withUserConfiguration(RabbitMQConfiguration.class)
      .withBean(RoutingKeyProperties.class, () -> ROUTING_KEYS)
      .withBean(QueueProperties.class, () -> QUEUES)
      .withBean(ExchangeProperties.class, () -> EXCHANGES)
      .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
      .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
      .run(context -> assertThat(context)
        .hasSingleBean(Declarables.class)
        .hasSingleBean(MessageRecoverer.class)
        .hasSingleBean(RabbitListenerRetrySettingsCustomizer.class));
  }

  @Test
  @DisplayName("configura 3 tentativas com backoff exponencial e não reenfileira a rejeição")
  void configuraTresTentativasComBackoffExponencial() throws IOException {
    final var listener = propriedadesDoApplicationYml().getListener().getSimple();

    assertThat(listener.getDefaultRequeueRejected()).isFalse();
    assertThat(listener.getRetry().isEnabled()).isTrue();
    assertThat(listener.getRetry().getMaxRetries()).isEqualTo(2);
    assertThat(listener.getRetry().getInitialInterval()).isEqualTo(Duration.ofSeconds(1));
    assertThat(listener.getRetry().getMultiplier()).isEqualTo(2.0);
    assertThat(listener.getRetry().getMaxInterval()).isEqualTo(Duration.ofSeconds(10));
  }

  /**
   * Lê o {@code application.yml} de verdade, e não valores montados no teste: o Boot ignora
   * em silêncio uma chave que não existe, então uma propriedade com o nome errado (como o
   * {@code max-attempts} do Boot 3) deixaria o retry no valor padrão sem nenhum aviso.
   */
  private static RabbitProperties propriedadesDoApplicationYml() throws IOException {
    final var ambiente = new StandardEnvironment();
    new YamlPropertySourceLoader()
      .load("application", new ClassPathResource("application.yml"))
      .forEach(ambiente.getPropertySources()::addLast);
    return Binder.get(ambiente).bind("spring.rabbitmq", RabbitProperties.class).get();
  }

}
