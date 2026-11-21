package com.hotel.booking.system.booking.service.infrastructure.configuration;

import com.hotel.booking.system.booking.service.application.scheduler.BookingExpirationScheduler;
import com.hotel.booking.system.booking.service.core.application.usecase.ExpirePendingBookingsUseCaseImpl;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.QueueProperties;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.RoutingKeyProperties;
import com.hotel.booking.system.commons.core.application.idempotency.IdempotentEventConsumer;
import com.hotel.booking.system.commons.infrastructure.messaging.DeadLetterRetryConfiguration;
import com.hotel.booking.system.commons.infrastructure.messaging.DeadLetterTopology;
import com.hotel.booking.system.commons.infrastructure.messaging.EventMessageConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Dead-letter e retry da mensageria do serviço de reservas")
class DeadLetterConfigurationTest {

  private static final RoutingKeyProperties ROUTING_KEYS = new RoutingKeyProperties("r1", "r2", "r3");

  private static final QueueProperties QUEUES = new QueueProperties("q1", "q2", "q3");

  private static final ExchangeProperties EXCHANGES = new ExchangeProperties("booking");

  private final RabbitMQConfiguration config = new RabbitMQConfiguration(ROUTING_KEYS, QUEUES, EXCHANGES);

  @Test
  @DisplayName("aponta cada fila de negócio para a DLQ dela")
  void apontaCadaFilaParaASuaDlq() {
    final var queues = List.of(
      this.config.bookingRoomRequestedQueue(),
      this.config.bookingRoomConfirmationQueue(),
      this.config.bookingRoomStatusChangedQueue()
    );

    assertThat(queues).allSatisfy(queue -> assertThat(queue.getArguments())
      .containsEntry("x-dead-letter-exchange", DeadLetterTopology.EXCHANGE)
      .containsEntry("x-dead-letter-routing-key", queue.getName() + ".dlq"));
  }

  @Test
  @DisplayName("declara uma DLQ para cada uma das três filas")
  void declaraUmaDlqParaCadaFila() {
    assertThat(this.config.deadLetterDeclarables().getDeclarablesByType(Queue.class))
      .extracting(Queue::getName)
      .containsExactlyInAnyOrder("q1.dlq", "q2.dlq", "q3.dlq");
  }

  @Test
  @DisplayName("usa o conversor de eventos do commons")
  void usaOConversorDeEventosDoCommons() {
    assertThat(this.config.jsonMessageConverter(new JsonMapperConfiguration().jsonMapper()))
      .isInstanceOf(EventMessageConverter.class);
  }

  @Test
  @DisplayName("registra a DLQ, o recoverer, o classificador de retry e o consumidor idempotente no contexto")
  void registraDlqRecovererEClassificadorNoContexto() {
    new ApplicationContextRunner()
      .withUserConfiguration(RabbitMQConfiguration.class)
      .withBean(RoutingKeyProperties.class, () -> ROUTING_KEYS)
      .withBean(QueueProperties.class, () -> QUEUES)
      .withBean(ExchangeProperties.class, () -> EXCHANGES)
      .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
      .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
      .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
      .withBean(org.springframework.transaction.PlatformTransactionManager.class, () -> mock(org.springframework.transaction.PlatformTransactionManager.class))
      .run(context -> assertThat(context)
        .hasSingleBean(Declarables.class)
        .hasSingleBean(MessageRecoverer.class)
        .hasSingleBean(RabbitListenerRetrySettingsCustomizer.class)
        .hasSingleBean(IdempotentEventConsumer.class)
        .hasSingleBean(com.hotel.booking.system.commons.core.application.outbox.Outbox.class)
        .hasSingleBean(com.hotel.booking.system.commons.infrastructure.messaging.outbox.OutboxRelay.class)
        .hasSingleBean(com.hotel.booking.system.commons.infrastructure.messaging.outbox.OutboxPurgeJob.class));
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

  @Test
  @DisplayName("espalha a espera do retry com 500 ms de jitter")
  void configuraJitterNoRetry() throws IOException {
    final var jitter = Binder.get(ambienteDoApplicationYml())
      .bind(DeadLetterRetryConfiguration.JITTER_PROPERTY, Duration.class);

    assertThat(jitter.isBound()).isTrue();
    assertThat(jitter.get()).isEqualTo(Duration.ofMillis(500));
  }

  @Test
  @DisplayName("dá três threads ao agendador e configura o outbox: relay a cada 1 s, orçamento de 5 s, um dia de retenção e expurgo de hora em hora")
  void agendaEmPoolEConfiguraOOutbox() throws IOException {
    final var binder = Binder.get(ambienteDoApplicationYml());

    assertThat(binder.bind("spring.task.scheduling.pool.size", Integer.class).get()).isEqualTo(3);
    assertThat(binder.bind("app.outbox.relay-interval", Duration.class).get()).isEqualTo(Duration.ofSeconds(1));
    assertThat(binder.bind("app.outbox.round-budget", Duration.class).get()).isEqualTo(Duration.ofSeconds(5));
    assertThat(binder.bind("app.outbox.retention", Duration.class).get()).isEqualTo(Duration.ofDays(1));
    assertThat(binder.bind("app.outbox.purge-interval", Duration.class).get()).isEqualTo(Duration.ofHours(1));
  }

  @Test
  @DisplayName("limita a espera pelo lock do quarto em 10 s")
  void limitaAEsperaPeloLockDoQuartoEm10Segundos() throws IOException {
    final var limite = Binder.get(ambienteDoApplicationYml())
      .bind("app.booking.room-lock-timeout", Duration.class);

    assertThat(limite.isBound()).isTrue();
    assertThat(limite.get()).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  @DisplayName("configura a expiração: prazo de 10 minutos, cron de 5 em 5 minutos e lotes de 100")
  void configuraAExpiracaoDeReservasPendentes() throws IOException {
    final var binder = Binder.get(ambienteDoApplicationYml());

    assertThat(binder.bind("app.booking.pending-timeout", Duration.class).get()).isEqualTo(Duration.ofMinutes(10));
    assertThat(binder.bind("app.booking.expiration-scheduler.cron", String.class).get()).isEqualTo("0 */5 * * * *");
    assertThat(binder.bind("app.booking.expiration-batch-size", Integer.class).get()).isEqualTo(100);
  }

  /**
   * O código da expiração não tem default: a chave que ele lê precisa existir no yml, ou o
   * serviço não sobe. Aqui o mesmo vale antes do deploy: toda expressão {@code @Value} e a do
   * {@code @Scheduled} se resolve contra o {@code application.yml} real, então uma chave
   * digitada errada de qualquer um dos lados derruba o teste.
   */
  @Test
  @DisplayName("toda chave que o código da expiração lê existe no application.yml")
  void aExpiracaoSoLeChavesQueOYmlDefine() throws Exception {
    final var ambiente = ambienteDoApplicationYml();
    final var expressoes = new ArrayList<String>();
    expressoes.add(BookingExpirationScheduler.class.getMethod("expirePendingBookings").getAnnotation(Scheduled.class).cron());
    for (final var construtor : List.of(
      BookingExpirationScheduler.class.getConstructors()[0],
      ExpirePendingBookingsUseCaseImpl.class.getConstructors()[0]
    )) {
      Arrays.stream(construtor.getParameters())
        .map(parametro -> parametro.getAnnotation(Value.class))
        .filter(Objects::nonNull)
        .map(Value::value)
        .forEach(expressoes::add);
    }

    assertThat(expressoes).hasSize(3).allSatisfy(expressao ->
      assertThat(ambiente.resolveRequiredPlaceholders(expressao)).isNotBlank()
    );
  }

  /**
   * Lê o {@code application.yml} de verdade, e não valores montados no teste: o Boot ignora
   * em silêncio uma chave que não existe, então uma propriedade com o nome errado (como o
   * {@code max-attempts} do Boot 3) deixaria o retry no valor padrão sem nenhum aviso.
   */
  private static RabbitProperties propriedadesDoApplicationYml() throws IOException {
    return Binder.get(ambienteDoApplicationYml()).bind("spring.rabbitmq", RabbitProperties.class).get();
  }

  private static StandardEnvironment ambienteDoApplicationYml() throws IOException {
    final var ambiente = new StandardEnvironment();
    new YamlPropertySourceLoader()
      .load("application", new ClassPathResource("application.yml"))
      .forEach(ambiente.getPropertySources()::addLast);
    return ambiente;
  }

}
