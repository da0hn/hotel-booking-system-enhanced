package com.hotel.booking.system.booking.service.infrastructure.db;

import com.hotel.booking.system.booking.service.core.application.mapper.BookingUseCaseMapperImpl;
import com.hotel.booking.system.booking.service.core.application.messaging.BookingRoomRequestedHandlerImpl;
import com.hotel.booking.system.booking.service.core.application.service.BookingInitializer;
import com.hotel.booking.system.booking.service.core.application.service.VerifyRoomAvailability;
import com.hotel.booking.system.booking.service.core.application.usecase.BookingRoomUseCaseImpl;
import com.hotel.booking.system.booking.service.infrastructure.db.mapper.impl.BookingDatabaseMapperImpl;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters.BookingRepositoryAdapter;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters.RoomRepositoryAdapter;
import com.hotel.booking.system.booking.service.infrastructure.messaging.listener.BookingRoomRequestedRabbitMQListener;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.RoutingKeyProperties;
import com.hotel.booking.system.booking.service.infrastructure.messaging.publisher.BookingRoomResponsePublisherImpl;
import com.hotel.booking.system.commons.core.application.idempotency.IdempotentEventConsumer;
import com.hotel.booking.system.commons.core.application.outbox.Outbox;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomItemRepresentation;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomPendingEvent;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomRequestedEvent;
import com.hotel.booking.system.commons.infrastructure.messaging.idempotency.JdbcIdempotencyConfiguration;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.JacksonOutbox;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.JdbcOutboxRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A issue #7 de ponta a ponta no serviço de reservas, com a cadeia real — listener, consumidor
 * idempotente, handler, caso de uso, publisher, outbox e repositórios — sobre o PostgreSQL de
 * verdade. A resposta da reserva vai para o {@code outbox_event} pela mesma transação que grava a
 * reserva, então o que importa é <em>quantas linhas</em> o outbox recebe e o que o rollback leva
 * junto.
 *
 * <p>A causa que a issue descreve está aqui: {@code Booking.initialize()} gera um {@code BookingId}
 * novo a cada execução, então um {@code save} repetido insere outra linha em vez de atualizar, e o
 * {@code reservationOrderId} não tinha constraint de unicidade.</p>
 */
@Import({
  BookingRepositoryAdapter.class,
  RoomRepositoryAdapter.class,
  BookingDatabaseMapperImpl.class,
  JdbcIdempotencyConfiguration.class
})
@DisplayName("Idempotência do consumo no booking-service")
class BookingIdempotencyIT extends AbstractDatabaseIT {

  private static final String QUARTO_TRIPLO_DELUXE = "2223dc04-831a-4bac-aef5-e22195575cc6";

  private static final String EXCHANGE_DA_RESPOSTA = "exchange.booking-room";

  private static final String ROUTING_KEY_DA_RESPOSTA = "routing-key.booking-room-confirmation";

  @Autowired
  private BookingRepositoryAdapter bookings;

  @Autowired
  private RoomRepositoryAdapter rooms;

  @Autowired
  private IdempotentEventConsumer consumer;

  @Autowired
  private JdbcClient jdbc;

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private FalhaDepoisDeGravarOutbox outbox;

  private final String pedido = UUID.randomUUID().toString();

  private BookingRoomRequestedRabbitMQListener listener;

  @BeforeEach
  void montaACadeiaReal() {
    final var mapper = new BookingUseCaseMapperImpl();
    final var useCase = new BookingRoomUseCaseImpl(
      this.bookings,
      mapper,
      new VerifyRoomAvailability(this.bookings, this.rooms),
      new BookingInitializer()
    );
    this.outbox = new FalhaDepoisDeGravarOutbox(
      new JacksonOutbox(new JdbcOutboxRepository(this.jdbc), JsonMapper.builder().build())
    );
    final var publisher = new BookingRoomResponsePublisherImpl(
      this.outbox,
      new RoutingKeyProperties("request", ROUTING_KEY_DA_RESPOSTA, "change"),
      new ExchangeProperties(EXCHANGE_DA_RESPOSTA)
    );
    this.listener = new BookingRoomRequestedRabbitMQListener(
      new BookingRoomRequestedHandlerImpl(useCase, mapper, publisher),
      this.consumer
    );
  }

  @AfterEach
  void limpaOQueOsTestesSemTransacaoConfirmaram() {
    this.jdbc.sql("delete from booking_room where booking_id in (select id from booking where reservation_order_id = :pedido)")
      .param("pedido", this.pedido).update();
    this.jdbc.sql("delete from booking where reservation_order_id = :pedido").param("pedido", this.pedido).update();
    this.jdbc.sql("delete from processed_event where event_type like '%BookingRoomRequestedEvent'").update();
    this.jdbc.sql("delete from outbox_event where aggregate_id = :pedido").param("pedido", this.pedido).update();
  }

  /** Cenários 1 e 2 da issue: processado uma vez, a reentrega é descartada sem escrita nova. */
  @Test
  @DisplayName("grava uma reserva e responde uma vez quando o mesmo evento é entregue duas vezes")
  void mesmoEventoEntregueDuasVezesGravaUmaReserva() {
    final var evento = this.evento();

    this.listener.listen(evento);
    this.listener.listen(evento);

    assertThat(this.reservasDoPedido()).isEqualTo(1);
    assertThat(this.eventosProcessados(evento.getEventId())).isEqualTo(1);
    assertThat(this.respostasNoOutbox()).singleElement().satisfies(linha -> {
      assertThat(linha.tipo()).isEqualTo(BookingRoomPendingEvent.class.getName());
      assertThat(linha.exchange()).isEqualTo(EXCHANGE_DA_RESPOSTA);
      assertThat(linha.routingKey()).isEqualTo(ROUTING_KEY_DA_RESPOSTA);
      assertThat(linha.publicada()).as("só o relay marca a linha como publicada").isFalse();
    });
  }

  /**
   * Cenário 3 da issue: o {@code eventId} é outro, a reserva é a mesma. A deduplicação por
   * {@code eventId} não vê nada de errado; quem barra é a chave de negócio, antes da verificação
   * de disponibilidade — que, vendo a reserva do primeiro, responderia "quarto indisponível" para
   * um pedido que está reservado.
   */
  @Test
  @DisplayName("grava uma única reserva quando dois eventos distintos pedem o mesmo reservationOrderId")
  void doisEventosDoMesmoPedidoGravamUmaReserva() {
    this.listener.listen(this.evento());
    this.listener.listen(this.evento());

    assertThat(this.reservasDoPedido()).isEqualTo(1);
    assertThat(this.respostasNoOutbox()).hasSize(1);
  }

  /**
   * O erro 1 da issue: a falha chega depois de gravar o negócio e a resposta. O registro do
   * {@code eventId} e a linha do outbox vivem na transação do listener, então o rollback desfaz a
   * reserva, o registro <em>e</em> a resposta — e a nova tentativa, em vez de ser descartada como
   * repetida, processa de verdade e grava uma única resposta.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("desfaz a reserva, o registro e a resposta no outbox juntos quando a falha vem depois de gravar, e processa na nova tentativa")
  void falhaDepoisDeGravarDesfazReservaRegistroERespostaEAProximaTentativaProcessa() {
    final var evento = this.evento();
    final var tx = new TransactionTemplate(this.transactionManager);
    this.outbox.falharDepoisDeGravar(true);

    assertThatThrownBy(() -> tx.executeWithoutResult(status -> this.listener.listen(evento)))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("depois de gravar a resposta");

    assertThat(this.reservasDoPedido()).as("a reserva foi desfeita").isZero();
    assertThat(this.eventosProcessados(evento.getEventId())).as("o registro foi desfeito").isZero();
    assertThat(this.respostasNoOutbox()).as("a resposta no outbox foi desfeita").isEmpty();

    this.outbox.falharDepoisDeGravar(false);
    tx.executeWithoutResult(status -> this.listener.listen(evento));

    assertThat(this.reservasDoPedido()).isEqualTo(1);
    assertThat(this.eventosProcessados(evento.getEventId())).isEqualTo(1);
    assertThat(this.respostasNoOutbox()).hasSize(1);
  }

  @Test
  @DisplayName("não grava outra resposta no outbox quando o evento é repetido")
  void eventoRepetidoNaoGravaOutraResposta() {
    final var evento = this.evento();
    this.listener.listen(evento);

    this.listener.listen(evento);

    assertThat(this.respostasNoOutbox()).hasSize(1);
  }

  private BookingRoomRequestedEvent evento() {
    return BookingRoomRequestedEvent.builder()
      .reservationOrderId(this.pedido)
      .customerId(UUID.randomUUID().toString())
      .price(new BigDecimal("100.0000"))
      .guests(2)
      .checkIn(LocalDate.of(2040, 5, 10))
      .checkOut(LocalDate.of(2040, 5, 13))
      .rooms(List.of(BookingRoomItemRepresentation.builder()
        .roomId(QUARTO_TRIPLO_DELUXE)
        .quantity(1)
        .price(new BigDecimal("100.0000"))
        .build()))
      .build();
  }

  /**
   * O {@code save} do JPA só insere no flush, e a contagem é por JDBC: sem descarregar antes, a
   * reentrega descartada — que não dispara nenhuma consulta JPQL — deixaria a contagem enxergar
   * o banco antes da escrita. O flush não esconde defeito: só alinha o que o teste mede, e só vale quando há transação ativa.
   */
  private long reservasDoPedido() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      this.entityManager.flush();
    }
    return this.jdbc.sql("select count(*) from booking where reservation_order_id = :pedido")
      .param("pedido", this.pedido).query(Long.class).single();
  }

  private long eventosProcessados(final String eventId) {
    return this.jdbc.sql("select count(*) from processed_event where event_id = :id")
      .param("id", eventId).query(Long.class).single();
  }

  private List<LinhaDoOutbox> respostasNoOutbox() {
    return this.jdbc.sql("""
        select event_type, exchange, routing_key, published_at is not null as published
        from outbox_event
        where aggregate_id = :pedido
        order by seq
        """)
      .param("pedido", this.pedido)
      .query((rs, row) -> new LinhaDoOutbox(
        rs.getString("event_type"),
        rs.getString("exchange"),
        rs.getString("routing_key"),
        rs.getBoolean("published")
      ))
      .list();
  }

  private record LinhaDoOutbox(String tipo, String exchange, String routingKey, boolean publicada) {
  }

  /**
   * Grava pelo outbox real e, quando armado, falha logo depois de gravar: é a falha que chega
   * com a reserva, o registro do evento e a resposta já escritos, e que só o rollback da
   * transação do listener desfaz.
   */
  private static final class FalhaDepoisDeGravarOutbox implements Outbox {

    private final Outbox delegate;

    private boolean falharDepoisDeGravar;

    private FalhaDepoisDeGravarOutbox(final Outbox delegate) {
      this.delegate = delegate;
    }

    void falharDepoisDeGravar(final boolean falhar) {
      this.falharDepoisDeGravar = falhar;
    }

    @Override
    public void append(final String aggregateId, final String exchange, final String routingKey, final Object event) {
      this.delegate.append(aggregateId, exchange, routingKey, event);
      if (this.falharDepoisDeGravar) {
        throw new IllegalStateException("falha depois de gravar a resposta no outbox");
      }
    }
  }

}
