package com.hotel.booking.system.booking.service.infrastructure.db;

import com.hotel.booking.system.commons.core.application.exception.MessagePublishException;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomConfirmedEvent;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.JacksonOutbox;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.JdbcOutboxRepository;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.OutboxBrokerPublisher;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.OutboxPurgeJob;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.OutboxRelay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Prova o outbox contra o PostgreSQL do serviço, com o usuário restrito (issue #35).
 *
 * <p>{@code NOT_SUPPORTED} tira a transação que o {@code @DataJpaTest} abriria em volta do teste:
 * o rollback e as duas instâncias do relay precisam cada um da sua transação.</p>
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Outbox da saga")
class OutboxIT extends AbstractDatabaseIT {

  /**
   * O atraso que o relay agenda depois de cada falha de transporte: 1s, 2s, 4s… 256s e, da 10ª
   * em diante, o teto de cinco minutos. Duas falhas além da 10ª provam que o teto se mantém.
   */
  private static final List<Long> EXPECTED_BACKOFF_SECONDS = List.of(1L, 2L, 4L, 8L, 16L, 32L, 64L, 128L, 256L, 300L, 300L);

  /**
   * O PostgreSQL guarda microssegundos e o relógio do teste tem nanossegundos: a diferença
   * entre o atraso gravado e o esperado nunca passa de um milissegundo.
   */
  private static final Duration BACKOFF_TOLERANCE = Duration.ofMillis(1);

  /** Pacote confiável (o do contrato de eventos) com um tipo que nenhuma versão do código tem. */
  private static final String MISSING_EVENT_TYPE = "com.hotel.booking.system.commons.core.domain.event.NaoExiste";

  @Autowired
  private JdbcClient jdbc;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction;

  private JdbcOutboxRepository repository;

  private JacksonOutbox outbox;

  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  @BeforeEach
  void prepara() {
    this.transaction = new TransactionTemplate(this.transactionManager);
    this.repository = new JdbcOutboxRepository(this.jdbc);
    this.outbox = new JacksonOutbox(this.repository, this.jsonMapper);
  }

  @AfterEach
  void limpa() {
    this.transaction.executeWithoutResult(status ->
      this.jdbc.sql("delete from outbox_event").update()
    );
  }

  @Test
  @DisplayName("grava os dois eventos da reserva na mesma transação, na ordem, e o relay preenche published_at")
  void gravaNaMesmaTransacaoEPublicaNaOrdem() {
    final var order = UUID.randomUUID().toString();
    final var published = new ArrayList<String>();
    final var relay = this.relay(published, false);

    this.transaction.executeWithoutResult(status -> {
      this.outbox.append(order, "exchange.booking-room", "routing-key.booking-room-requested", this.event(order));
      this.outbox.append(order, "exchange.customer-booking", "routing-key.customer-booking-update", this.event(order));
    });

    assertThat(this.pendingCount()).isEqualTo(2);
    relay.publishPending();
    assertThat(published).containsExactly(
      "exchange.booking-room|routing-key.booking-room-requested",
      "exchange.customer-booking|routing-key.customer-booking-update"
    );
    assertThat(this.pendingCount()).isZero();
    final var marked = this.jdbc.sql("select count(*) from outbox_event where published_at is not null")
      .query(Long.class).single();
    assertThat(marked).isEqualTo(2L);
  }

  @Test
  @DisplayName("não publica o segundo evento do agregado antes do primeiro")
  void preservaAOrdemDoAgregado() {
    final var order = UUID.randomUUID().toString();
    this.transaction.executeWithoutResult(status -> {
      this.outbox.append(order, "exchange.a", "first", this.event(order));
      this.outbox.append(order, "exchange.a", "second", this.event(order));
    });

    final var first = this.transaction.execute(status -> this.repository.claimPending(10, Set.of(), Instant.now()));

    assertThat(first).hasSize(1);
    assertThat(first.getFirst().routingKey()).isEqualTo("first");
  }

  @Test
  @DisplayName("desfaz a linha quando a transação de negócio desfaz")
  void rollbackNaoDeixaLinha() {
    final var order = UUID.randomUUID().toString();

    assertThatThrownBy(() -> this.transaction.executeWithoutResult(status -> {
      this.outbox.append(order, "exchange.a", "key", this.event(order));
      throw new IllegalStateException("falha depois de gravar");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(this.jdbc.sql("select count(*) from outbox_event").query(Long.class).single()).isZero();
  }

  @Test
  @DisplayName("deixa a linha pendente quando o broker não confirma, e publica quando volta")
  void brokerForaDeixaPendenteEReprocessa() {
    final var order = UUID.randomUUID().toString();
    this.transaction.executeWithoutResult(status ->
      this.outbox.append(order, "exchange.a", "key", this.event(order))
    );
    final var clock = new MovableClock();
    this.relay(clock, new ArrayList<>(), true).publishPending();
    assertThat(this.pendingCount()).isEqualTo(1);

    clock.advance(Duration.ofMinutes(1));
    final var published = new ArrayList<String>();
    this.relay(clock, published, false).publishPending();
    assertThat(published).containsExactly("exchange.a|key");
    assertThat(this.pendingCount()).isZero();
  }

  @Test
  @DisplayName("o return sem fila abandona a linha e libera o evento seguinte do agregado")
  void mensagemSemFilaAbandonaELiberaOSeguinte() {
    final var order = UUID.randomUUID().toString();
    this.transaction.executeWithoutResult(status -> {
      this.outbox.append(order, "exchange.a", "sem-binding", this.event(order));
      this.outbox.append(order, "exchange.a", "seguinte", this.event(order));
    });
    final var template = mock(RabbitTemplate.class);
    final var published = new ArrayList<String>();
    doAnswer(invocation -> {
      final var routingKey = invocation.getArgument(1, String.class);
      final var correlation = invocation.getArgument(3, CorrelationData.class);
      if ("sem-binding".equals(routingKey)) {
        correlation.setReturned(new ReturnedMessage(
          new Message(new byte[0], new MessageProperties()),
          312,
          "NO_ROUTE",
          "exchange.a",
          "sem-binding"
        ));
      } else {
        published.add(routingKey);
      }
      correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
      return null;
    }).when(template).convertAndSend(any(String.class), any(String.class), any(Object.class), any(CorrelationData.class));
    final var relay = new OutboxRelay(
      this.repository,
      new OutboxBrokerPublisher(template),
      this.jsonMapper,
      this.transaction,
      Clock.systemUTC(),
      Duration.ofMinutes(1)
    );

    relay.publishPending();
    relay.publishPending();

    final var rows = this.rowsOf(order);
    assertThat(rows.getFirst().abandonedAt()).isNotNull();
    assertThat(rows.get(1).publishedAt()).isNotNull();
    assertThat(published).containsExactly("seguinte");
  }

  @Test
  @DisplayName("broker fora não abandona a linha, e o backoff segura o evento seguinte do agregado")
  void brokerForaNaoAbandonaESeguraOSeguinte() {
    final var order = UUID.randomUUID().toString();
    this.transaction.executeWithoutResult(status -> {
      this.outbox.append(order, "exchange.a", "primeiro", this.event(order));
      this.outbox.append(order, "exchange.a", "segundo", this.event(order));
    });
    final var clock = new MovableClock();
    for (var attempt = 1; attempt <= EXPECTED_BACKOFF_SECONDS.size(); attempt++) {
      this.relay(clock, new ArrayList<>(), true).publishPending();
      final var rows = this.rowsOf(order);
      final var head = rows.getFirst();
      assertThat(head.attempts()).isEqualTo(attempt);
      assertThat(head.abandonedAt()).isNull();
      assertThat(head.nextAttemptAt()).isNotNull();
      final var delay = Duration.between(clock.instant(), head.nextAttemptAt().toInstant());
      final var expected = Duration.ofSeconds(EXPECTED_BACKOFF_SECONDS.get(attempt - 1));
      assertThat(delay)
        .as("atraso depois da %dª falha", attempt)
        .isBetween(expected.minus(BACKOFF_TOLERANCE), expected.plus(BACKOFF_TOLERANCE));
      assertThat(rows.get(1).attempts()).isZero();
      assertThat(rows.get(1).publishedAt()).isNull();
      clock.advance(delay);
    }

    final var published = new ArrayList<String>();
    this.relay(clock, published, false).publishPending();
    assertThat(published).containsExactly("exchange.a|primeiro", "exchange.a|segundo");
    assertThat(this.rowsOf(order)).allSatisfy(row -> assertThat(row.publishedAt()).isNotNull());
  }

  @Test
  @DisplayName("a linha em backoff não é reservada de novo antes do next_attempt_at")
  void linhaEmBackoffSoVoltaDepoisDoNextAttemptAt() {
    final var order = UUID.randomUUID().toString();
    this.transaction.executeWithoutResult(status ->
      this.outbox.append(order, "exchange.a", "key", this.event(order))
    );
    final var template = mock(RabbitTemplate.class);
    doThrow(new MessagePublishException("broker fora"))
      .when(template).convertAndSend(any(String.class), any(String.class), any(Object.class), any(CorrelationData.class));
    final var clock = new MovableClock();
    final var relay = new OutboxRelay(
      this.repository,
      new OutboxBrokerPublisher(template),
      this.jsonMapper,
      this.transaction,
      clock,
      Duration.ofMinutes(1)
    );

    relay.publishPending();
    relay.publishPending();

    verify(template, times(1)).convertAndSend(any(String.class), any(String.class), any(Object.class), any(CorrelationData.class));
    assertThat(this.rowsOf(order).getFirst().attempts()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(1));
    relay.publishPending();

    verify(template, times(2)).convertAndSend(any(String.class), any(String.class), any(Object.class), any(CorrelationData.class));
    assertThat(this.rowsOf(order).getFirst().attempts()).isEqualTo(2);
  }

  @Test
  @DisplayName("o payload que não desserializa abandona a linha e libera o evento seguinte do agregado")
  void payloadInvalidoAbandonaELiberaOSeguinte() {
    final var order = UUID.randomUUID().toString();
    this.insertRaw(order, BookingRoomConfirmedEvent.class.getName(), "\"nao e um evento\"");
    this.transaction.executeWithoutResult(status ->
      this.outbox.append(order, "exchange.a", "seguinte", this.event(order))
    );
    final var published = new ArrayList<String>();
    final var relay = this.relay(published, false);

    relay.publishPending();
    relay.publishPending();

    final var rows = this.rowsOf(order);
    assertThat(rows.getFirst().abandonedAt()).isNotNull();
    assertThat(rows.getFirst().nextAttemptAt()).isNull();
    assertThat(rows.getFirst().attempts()).isEqualTo(1);
    assertThat(rows.get(1).publishedAt()).isNotNull();
    assertThat(published).containsExactly("exchange.a|seguinte");
  }

  @Test
  @DisplayName("a classe ausente do classpath, num pacote confiável, abandona a linha e libera o evento seguinte")
  void classeAusenteAbandonaELiberaOSeguinte() {
    final var order = UUID.randomUUID().toString();
    this.insertRaw(order, MISSING_EVENT_TYPE, "{}");
    this.transaction.executeWithoutResult(status ->
      this.outbox.append(order, "exchange.a", "seguinte", this.event(order))
    );
    final var published = new ArrayList<String>();
    final var relay = this.relay(published, false);

    relay.publishPending();
    relay.publishPending();

    final var rows = this.rowsOf(order);
    assertThat(rows.getFirst().abandonedAt()).isNotNull();
    assertThat(rows.getFirst().nextAttemptAt()).isNull();
    assertThat(rows.getFirst().attempts()).isEqualTo(1);
    assertThat(rows.get(1).publishedAt()).isNotNull();
    assertThat(published).containsExactly("exchange.a|seguinte");
  }

  @Test
  @DisplayName("duas instâncias não reservam o mesmo evento")
  void duasInstanciasNaoPublicamOMesmoEvento() throws Exception {
    final var firstOrder = UUID.randomUUID().toString();
    final var secondOrder = UUID.randomUUID().toString();
    this.transaction.executeWithoutResult(status -> {
      this.outbox.append(firstOrder, "exchange.a", "one", this.event(firstOrder));
      this.outbox.append(secondOrder, "exchange.a", "two", this.event(secondOrder));
    });
    final var holding = new CountDownLatch(2);
    final var release = new CountDownLatch(1);
    final var claimed = new ArrayList<UUID>();
    final var executor = Executors.newFixedThreadPool(2);
    try {
      for (var i = 0; i < 2; i++) {
        executor.submit(() -> this.transaction.executeWithoutResult(status -> {
          final var rows = this.repository.claimPending(1, Set.of(), Instant.now());
          synchronized (claimed) {
            claimed.add(rows.getFirst().id());
          }
          holding.countDown();
          try {
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
          } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
          }
        }));
      }
      assertThat(holding.await(5, TimeUnit.SECONDS))
        .as("a segunda instância ficou bloqueada no mesmo evento; o skip locked não segurou")
        .isTrue();
      assertThat(claimed).hasSize(2).doesNotHaveDuplicates();
    } finally {
      release.countDown();
      executor.shutdown();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("uma falha de um agregado não impede o outro, e o expurgo só apaga o que já foi publicado")
  void falhaNaoBloqueiaOutroAgregadoEExpurgoIgnoraPendente() {
    final var stuck = UUID.randomUUID().toString();
    final var healthy = UUID.randomUUID().toString();
    this.transaction.executeWithoutResult(status -> {
      this.outbox.append(stuck, "exchange.stuck", "key", this.event(stuck));
      this.outbox.append(healthy, "exchange.ok", "key", this.event(healthy));
    });
    final var attempts = new AtomicInteger();
    final var template = mock(RabbitTemplate.class);
    doAnswer(invocation -> {
      final var exchange = invocation.getArgument(0, String.class);
      if ("exchange.stuck".equals(exchange)) {
        throw new MessagePublishException("sem fila");
      }
      final var correlation = invocation.getArgument(3, CorrelationData.class);
      correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
      attempts.incrementAndGet();
      return null;
    }).when(template).convertAndSend(any(String.class), any(String.class), any(Object.class), any(CorrelationData.class));
    new OutboxRelay(this.repository, new OutboxBrokerPublisher(template), this.jsonMapper, this.transaction, Clock.systemUTC(), Duration.ofMinutes(1))
      .publishPending();

    assertThat(attempts).hasValue(1);
    assertThat(this.jdbc.sql("select count(*) from outbox_event where published_at is null and exchange = 'exchange.stuck'")
      .query(Long.class).single()).isEqualTo(1L);
    assertThat(this.jdbc.sql("select count(*) from outbox_event where published_at is not null and exchange = 'exchange.ok'")
      .query(Long.class).single()).isEqualTo(1L);

    final var retention = Duration.ofDays(7);
    final var insideRetention = Clock.fixed(Instant.now().plus(Duration.ofDays(6)), ZoneOffset.UTC);
    new OutboxPurgeJob(this.repository, retention, insideRetention).purge();
    assertThat(this.jdbc.sql("select count(*) from outbox_event where exchange = 'exchange.ok'").query(Long.class).single())
      .as("a linha publicada há menos que a retenção permanece")
      .isEqualTo(1L);

    final var pastRetention = Clock.fixed(Instant.now().plus(Duration.ofDays(8)), ZoneOffset.UTC);
    new OutboxPurgeJob(this.repository, retention, pastRetention).purge();
    assertThat(this.jdbc.sql("select count(*) from outbox_event where exchange = 'exchange.ok'").query(Long.class).single()).isZero();
    assertThat(this.jdbc.sql("select count(*) from outbox_event where exchange = 'exchange.stuck'").query(Long.class).single()).isEqualTo(1L);
  }

  private List<OutboxRow> rowsOf(final String aggregateId) {
    return this.jdbc.sql("""
        select attempts, next_attempt_at, abandoned_at, published_at
        from outbox_event
        where aggregate_id = :aggregate
        order by seq
        """)
      .param("aggregate", aggregateId)
      .query((rs, row) -> new OutboxRow(
        rs.getInt("attempts"),
        rs.getObject("next_attempt_at", OffsetDateTime.class),
        rs.getObject("abandoned_at", OffsetDateTime.class),
        rs.getObject("published_at", OffsetDateTime.class)
      ))
      .list();
  }

  private record OutboxRow(
    int attempts,
    OffsetDateTime nextAttemptAt,
    OffsetDateTime abandonedAt,
    OffsetDateTime publishedAt
  ) {
  }

  private long pendingCount() {
    return this.jdbc.sql("select count(*) from outbox_event where published_at is null").query(Long.class).single();
  }

  private OutboxRelay relay(final List<String> published, final boolean fail) {
    return this.relay(Clock.systemUTC(), published, fail);
  }

  private OutboxRelay relay(final Clock clock, final List<String> published, final boolean fail) {
    final var template = mock(RabbitTemplate.class);
    doAnswer(invocation -> {
      if (fail) {
        throw new MessagePublishException("broker fora");
      }
      published.add(invocation.getArgument(0, String.class) + "|" + invocation.getArgument(1, String.class));
      final var correlation = invocation.getArgument(3, CorrelationData.class);
      correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
      return null;
    }).when(template).convertAndSend(any(String.class), any(String.class), any(Object.class), any(CorrelationData.class));
    return new OutboxRelay(
      this.repository,
      new OutboxBrokerPublisher(template),
      this.jsonMapper,
      this.transaction,
      clock,
      Duration.ofMinutes(1)
    );
  }

  @Test
  @DisplayName("não instancia nem publica um event_type fora dos pacotes confiáveis")
  void tipoForaDosPacotesConfiaveisNaoEPublicado() {
    final var aggregate = UUID.randomUUID().toString();
    this.insertRaw(aggregate, "java.lang.Runtime", "{}");
    final var published = new ArrayList<String>();
    this.relay(published, false).publishPending();

    assertThat(published).isEmpty();
    assertThat(this.rowsOf(aggregate).getFirst().abandonedAt()).isNotNull();
  }

  /**
   * Grava a linha por SQL, sem passar pelo {@code JacksonOutbox}: é o único jeito de pôr no
   * banco um tipo que não existe ou um payload que o tipo não aceita, que o relay precisa
   * tratar como defeito da própria linha.
   */
  private void insertRaw(final String aggregateId, final String eventType, final String payload) {
    this.transaction.executeWithoutResult(status -> this.jdbc.sql("""
        insert into outbox_event (id, aggregate_id, event_type, exchange, routing_key, payload)
        values (:id, :aggregate, :eventType, 'exchange.a', 'key', cast(:payload as jsonb))
        """)
      .param("id", UUID.randomUUID())
      .param("aggregate", aggregateId)
      .param("eventType", eventType)
      .param("payload", payload)
      .update());
  }

  private static final class MovableClock extends Clock {

    private Instant instant = Instant.now();

    void advance(final Duration duration) {
      this.instant = this.instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return this.instant;
    }
  }

  private BookingRoomConfirmedEvent event(final String order) {
    return BookingRoomConfirmedEvent.builder()
      .reservationOrderId(order)
      .customerId(UUID.randomUUID().toString())
      .status(CustomerReservationStatus.RESERVED)
      .build();
  }

}
