package com.hotel.booking.system.hotel.service.infrastructure.db;

import com.hotel.booking.system.commons.core.application.exception.MessagePublishException;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomRequestedEvent;
import com.hotel.booking.system.commons.core.domain.event.customer.CustomerBookingInitiatedEvent;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.JacksonOutbox;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.JdbcOutboxRepository;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.OutboxBrokerPublisher;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.OutboxRelay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Prova o outbox contra o schema do {@code hotel-service}, com o usuário restrito dele.
 *
 * <p>As migrations {@code V014} a {@code V016} são cópia das do {@code booking-service}, e o
 * {@code OutboxIT} de lá só roda naquele schema. Sem este teste a DDL do hotel só era conferida
 * pelo nome das colunas: um {@code aggregate_id varchar(8)} ou um {@code attempts default 5}
 * passavam por todo o {@code mvn verify}. Aqui o {@code reservationOrderId} (um UUID de 36
 * caracteres) entra na coluna, o relay percorre a ordem do agregado, e o atraso da primeira falha
 * é o de 1 s que parte de zero tentativa.</p>
 *
 * <p>{@code NOT_SUPPORTED} tira a transação que o {@code @DataJpaTest} abriria em volta do teste:
 * o rollback e o relay precisam cada um da sua transação.</p>
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Outbox do hotel-service")
class OutboxIT extends AbstractDatabaseIT {

  /** O PostgreSQL guarda microssegundos e o relógio do teste, nanossegundos. */
  private static final Duration TOLERANCIA = Duration.ofMillis(1);

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
    this.transaction.executeWithoutResult(status -> this.jdbc.sql("delete from outbox_event").update());
  }

  @Test
  @DisplayName("grava os dois eventos da reserva na mesma transação, o relay os publica na ordem e preenche published_at")
  void gravaNaMesmaTransacaoEPublicaNaOrdem() {
    final var pedido = UUID.randomUUID().toString();
    final var publicados = new ArrayList<String>();
    final var relay = this.relay(Clock.systemUTC(), this.templateQueConfirma(publicados));

    this.transaction.executeWithoutResult(status -> {
      this.outbox.append(pedido, "exchange.booking-room", "routing-key.booking-room-requested", this.pedidoAoBooking(pedido));
      this.outbox.append(pedido, "exchange.customer-booking", "routing-key.customer-booking-update", this.registroNoCustomer(pedido));
    });

    assertThat(this.linhasDe(pedido)).extracting(Linha::publishedAt).containsOnlyNulls();
    relay.publishPending();
    relay.publishPending();

    assertThat(publicados).containsExactly(
      "exchange.booking-room|routing-key.booking-room-requested",
      "exchange.customer-booking|routing-key.customer-booking-update"
    );
    assertThat(this.linhasDe(pedido)).extracting(Linha::publishedAt).doesNotContainNull();
  }

  @Test
  @DisplayName("desfaz as duas linhas quando a transação que gravou desfaz")
  void rollbackNaoDeixaNenhumaDasDuasLinhas() {
    final var pedido = UUID.randomUUID().toString();

    assertThatThrownBy(() -> this.transaction.executeWithoutResult(status -> {
      this.outbox.append(pedido, "exchange.booking-room", "routing-key.booking-room-requested", this.pedidoAoBooking(pedido));
      this.outbox.append(pedido, "exchange.customer-booking", "routing-key.customer-booking-update", this.registroNoCustomer(pedido));
      throw new IllegalStateException("falha depois de gravar os dois eventos");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(this.linhasDe(pedido)).isEmpty();
  }

  @Test
  @DisplayName("agenda a primeira falha do broker para um segundo depois, a partir de zero tentativas")
  void primeiraFalhaAgendaUmSegundo() {
    final var pedido = UUID.randomUUID().toString();
    this.transaction.executeWithoutResult(status ->
      this.outbox.append(pedido, "exchange.booking-room", "routing-key.booking-room-requested", this.pedidoAoBooking(pedido))
    );
    final var agora = Instant.now();
    final var template = mock(RabbitTemplate.class);
    doThrow(new MessagePublishException("broker fora"))
      .when(template).convertAndSend(any(String.class), any(String.class), any(Object.class), any(CorrelationData.class));

    this.relay(Clock.fixed(agora, ZoneOffset.UTC), template).publishPending();

    final var linha = this.linhasDe(pedido).getFirst();
    assertThat(linha.attempts()).isEqualTo(1);
    assertThat(linha.abandonedAt()).isNull();
    assertThat(linha.publishedAt()).isNull();
    assertThat(Duration.between(agora, linha.nextAttemptAt().toInstant()))
      .isBetween(Duration.ofSeconds(1).minus(TOLERANCIA), Duration.ofSeconds(1).plus(TOLERANCIA));
  }

  private OutboxRelay relay(final Clock clock, final RabbitTemplate template) {
    return new OutboxRelay(
      this.repository,
      new OutboxBrokerPublisher(template),
      this.jsonMapper,
      this.transaction,
      clock,
      Duration.ofMinutes(1)
    );
  }

  private RabbitTemplate templateQueConfirma(final List<String> publicados) {
    final var template = mock(RabbitTemplate.class);
    doAnswer(invocation -> {
      publicados.add(invocation.getArgument(0, String.class) + "|" + invocation.getArgument(1, String.class));
      invocation.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true, null));
      return null;
    }).when(template).convertAndSend(any(String.class), any(String.class), any(Object.class), any(CorrelationData.class));
    return template;
  }

  private List<Linha> linhasDe(final String agregado) {
    return this.jdbc.sql("""
        select attempts, next_attempt_at, abandoned_at, published_at
        from outbox_event
        where aggregate_id = :agregado
        order by seq
        """)
      .param("agregado", agregado)
      .query((rs, row) -> new Linha(
        rs.getInt("attempts"),
        rs.getObject("next_attempt_at", OffsetDateTime.class),
        rs.getObject("abandoned_at", OffsetDateTime.class),
        rs.getObject("published_at", OffsetDateTime.class)
      ))
      .list();
  }

  private record Linha(int attempts, OffsetDateTime nextAttemptAt, OffsetDateTime abandonedAt, OffsetDateTime publishedAt) {
  }

  private BookingRoomRequestedEvent pedidoAoBooking(final String pedido) {
    return BookingRoomRequestedEvent.builder()
      .reservationOrderId(pedido)
      .customerId(UUID.randomUUID().toString())
      .build();
  }

  private CustomerBookingInitiatedEvent registroNoCustomer(final String pedido) {
    return CustomerBookingInitiatedEvent.builder()
      .reservationOrderId(pedido)
      .customerId(UUID.randomUUID().toString())
      .status(CustomerReservationStatus.AWAITING_RESERVATION)
      .build();
  }

}
