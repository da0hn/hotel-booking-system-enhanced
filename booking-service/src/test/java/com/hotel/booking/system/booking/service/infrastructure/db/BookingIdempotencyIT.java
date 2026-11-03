package com.hotel.booking.system.booking.service.infrastructure.db;

import com.hotel.booking.system.booking.service.core.application.mapper.BookingUseCaseMapperImpl;
import com.hotel.booking.system.booking.service.core.application.messaging.BookingRoomRequestedHandlerImpl;
import com.hotel.booking.system.booking.service.core.application.service.BookingInitializer;
import com.hotel.booking.system.booking.service.core.application.service.VerifyRoomAvailability;
import com.hotel.booking.system.booking.service.core.application.usecase.BookingRoomUseCaseImpl;
import com.hotel.booking.system.booking.service.core.ports.spi.messaging.publisher.BookingRoomResponsePublisher;
import com.hotel.booking.system.booking.service.infrastructure.db.mapper.impl.BookingDatabaseMapperImpl;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters.BookingRepositoryAdapter;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters.RoomRepositoryAdapter;
import com.hotel.booking.system.booking.service.infrastructure.messaging.listener.BookingRoomRequestedRabbitMQListener;
import com.hotel.booking.system.commons.core.application.exception.MessagePublishException;
import com.hotel.booking.system.commons.core.application.idempotency.IdempotentEventConsumer;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomItemRepresentation;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomPendingEvent;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomRequestedEvent;
import com.hotel.booking.system.commons.infrastructure.messaging.idempotency.JdbcIdempotencyConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A issue #7 de ponta a ponta no serviço de reservas, com a cadeia real — listener, consumidor
 * idempotente, handler, caso de uso e repositórios — sobre o PostgreSQL de verdade. Só o publisher
 * é simulado, porque o que importa é <em>quantas vezes</em> ele é chamado.
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

  private final BookingRoomResponsePublisher publisher = mock(BookingRoomResponsePublisher.class);

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
    this.listener = new BookingRoomRequestedRabbitMQListener(
      new BookingRoomRequestedHandlerImpl(useCase, mapper, this.publisher),
      this.consumer
    );
  }

  @AfterEach
  void limpaOQueOsTestesSemTransacaoConfirmaram() {
    this.jdbc.sql("delete from booking_room where booking_id in (select id from booking where reservation_order_id = :pedido)")
      .param("pedido", this.pedido).update();
    this.jdbc.sql("delete from booking where reservation_order_id = :pedido").param("pedido", this.pedido).update();
    this.jdbc.sql("delete from processed_event where event_type like '%BookingRoomRequestedEvent'").update();
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
    final var enviada = ArgumentCaptor.forClass(com.hotel.booking.system.commons.core.domain.event.BookingRoomResponseEvent.class);
    verify(this.publisher, times(1)).publish(enviada.capture());
    assertThat(enviada.getValue()).isInstanceOf(BookingRoomPendingEvent.class);
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
    verify(this.publisher, times(1)).publish(any());
  }

  /**
   * O erro 1 da issue: a falha chega depois de gravar o negócio. O registro do {@code eventId}
   * vive na transação do listener, então o rollback desfaz a reserva <em>e</em> o registro — e a
   * nova tentativa, em vez de ser descartada como repetida, processa de verdade.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("desfaz a reserva e o registro juntos quando a publicação falha, e processa na nova tentativa")
  void falhaDepoisDeGravarDesfazReservaERegistroEAProximaTentativaProcessa() {
    final var evento = this.evento();
    final var tx = new TransactionTemplate(this.transactionManager);
    doThrow(new MessagePublishException("broker offline")).when(this.publisher).publish(any());

    assertThatThrownBy(() -> tx.executeWithoutResult(status -> this.listener.listen(evento)))
      .isInstanceOf(MessagePublishException.class);

    assertThat(this.reservasDoPedido()).as("a reserva foi desfeita").isZero();
    assertThat(this.eventosProcessados(evento.getEventId())).as("o registro foi desfeito").isZero();

    doNothing().when(this.publisher).publish(any());
    tx.executeWithoutResult(status -> this.listener.listen(evento));

    assertThat(this.reservasDoPedido()).isEqualTo(1);
    assertThat(this.eventosProcessados(evento.getEventId())).isEqualTo(1);
  }

  @Test
  @DisplayName("não publica nada quando o evento é repetido")
  void eventoRepetidoNaoPublicaNada() {
    final var evento = this.evento();
    this.listener.listen(evento);
    org.mockito.Mockito.clearInvocations(this.publisher);

    this.listener.listen(evento);

    verifyNoInteractions(this.publisher);
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

}
