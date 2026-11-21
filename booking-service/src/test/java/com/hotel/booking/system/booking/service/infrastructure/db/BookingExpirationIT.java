package com.hotel.booking.system.booking.service.infrastructure.db;

import com.hotel.booking.system.booking.service.application.scheduler.BookingExpirationScheduler;
import com.hotel.booking.system.booking.service.core.application.service.VerifyRoomAvailability;
import com.hotel.booking.system.booking.service.core.application.usecase.ExpirePendingBookingsUseCaseImpl;
import com.hotel.booking.system.booking.service.core.domain.exception.BookingDomainException;
import com.hotel.booking.system.booking.service.core.ports.spi.messaging.publisher.BookingRoomResponsePublisher;
import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.booking.service.core.domain.entity.BookingPeriod;
import com.hotel.booking.system.booking.service.core.domain.entity.BookingRoom;
import com.hotel.booking.system.booking.service.infrastructure.db.mapper.impl.BookingDatabaseMapperImpl;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.BookingJpaRepository;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters.BookingRepositoryAdapter;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters.RoomRepositoryAdapter;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.RoutingKeyProperties;
import com.hotel.booking.system.booking.service.infrastructure.messaging.publisher.BookingRoomResponsePublisherImpl;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomExpiredEvent;
import com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerId;
import com.hotel.booking.system.commons.core.domain.valueobject.Money;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.JacksonOutbox;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.JdbcOutboxRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Expiração de reservas {@code PENDING} contra o PostgreSQL (issue #8).
 */
@Import({BookingRepositoryAdapter.class, RoomRepositoryAdapter.class, BookingDatabaseMapperImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Expiração de reservas pendentes")
class BookingExpirationIT extends AbstractDatabaseIT {

  private static final RoomId ROOM = RoomId.of("2223dc04-831a-4bac-aef5-e22195575cc6");

  private static final LocalDate CHECK_IN = LocalDate.of(2041, 5, 1);

  private static final LocalDate CHECK_OUT = LocalDate.of(2041, 5, 4);

  @Autowired
  private BookingRepositoryAdapter bookings;

  @Autowired
  private RoomRepositoryAdapter rooms;

  @Autowired
  private BookingJpaRepository jpa;

  @Autowired
  private JdbcClient jdbc;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private EntityManagerFactory entityManagerFactory;

  private TransactionTemplate transaction;

  private BookingRoomResponsePublisherImpl publisher;

  private BookingExpirationScheduler scheduler;

  @BeforeEach
  void prepara() {
    this.transaction = new TransactionTemplate(this.transactionManager);
    final var outbox = new JacksonOutbox(new JdbcOutboxRepository(this.jdbc), JsonMapper.builder().build());
    this.publisher = new BookingRoomResponsePublisherImpl(
      outbox,
      new RoutingKeyProperties("requested", "confirmation", "status"),
      new ExchangeProperties("exchange.booking-room")
    );
    this.scheduler = this.schedulerComLotesDe(100, this.publisher);
  }

  private BookingExpirationScheduler schedulerComLotesDe(final int tamanho, final BookingRoomResponsePublisher destino) {
    return new BookingExpirationScheduler(
      new ExpirePendingBookingsUseCaseImpl(this.bookings, Duration.ofMinutes(10)),
      destino,
      this.transactionManager,
      tamanho
    );
  }

  @AfterEach
  void limpa() {
    this.transaction.executeWithoutResult(status -> {
      this.jdbc.sql("""
        delete from booking_room
        where booking_id in (select id from booking where check_in = :checkIn)
        """).param("checkIn", CHECK_IN).update();
      this.jdbc.sql("delete from booking where check_in = :checkIn").param("checkIn", CHECK_IN).update();
      this.jdbc.sql("delete from outbox_event").update();
    });
  }

  @Test
  @DisplayName("não cancela a reserva que ainda está dentro do prazo")
  void pagamentoNoPrazoNaoInterfere() {
    final var order = this.gravaPendente(false);

    this.transaction.executeWithoutResult(status -> this.scheduler.expirePendingBookings());

    assertThat(this.statusOf(order)).isEqualTo("PENDING");
    assertThat(this.jdbc.sql("select count(*) from outbox_event").query(Long.class).single()).isZero();
  }

  @Test
  @DisplayName("cancela a reserva vencida, publica o evento e libera o quarto")
  void expiracaoCancelaPublicaELiberaOQuarto() {
    final var order = this.gravaPendente(true);
    final var cliente = this.customerOf(order);

    this.transaction.executeWithoutResult(status -> this.scheduler.expirePendingBookings());

    assertThat(this.statusOf(order)).isEqualTo("CANCELED");
    final var linhas = this.jdbc.sql("select event_type, payload::text from outbox_event")
      .query((rs, row) -> new String[] {rs.getString(1), rs.getString(2)})
      .list();
    assertThat(linhas).singleElement().satisfies(linha -> {
      assertThat(linha[0]).isEqualTo(BookingRoomExpiredEvent.class.getName());
      final var evento = JsonMapper.builder().build().readValue(linha[1], BookingRoomExpiredEvent.class);
      assertThat(evento.getReservationOrderId()).isEqualTo(order.toString());
      assertThat(evento.getCustomerId()).as("o cliente da reserva, e não o id do pedido").isEqualTo(cliente);
      assertThat(evento.getStatus()).isEqualTo(BookingStatus.CANCELED);
      assertThat(evento.getFailureMessages()).containsExactly("A reserva expirou aguardando confirmacao de pagamento");
    });
    assertThat(this.jpa.sumOccupiedUnits(java.util.List.of(ROOM.getValue()), CHECK_IN, CHECK_OUT)).isEmpty();

    final var novo = this.novaReserva();
    final var falhas = this.transaction.execute(status ->
      new VerifyRoomAvailability(this.bookings, this.rooms).execute(novo)
    );
    assertThat(falhas.data()).isEmpty();
  }

  @Test
  @DisplayName("repete os lotes na mesma execução até cancelar todas as reservas vencidas")
  void repeteOsLotesAteEsvaziar() {
    final var ordens = List.of(this.gravaPendenteHa(30), this.gravaPendenteHa(29), this.gravaPendenteHa(28));

    this.schedulerComLotesDe(2, this.publisher).expirePendingBookings();

    assertThat(ordens).allSatisfy(ordem -> assertThat(this.statusOf(ordem)).isEqualTo("CANCELED"));
    assertThat(this.jdbc.sql("select count(*) from outbox_event").query(Long.class).single()).isEqualTo(3L);
  }

  /**
   * O scheduler chama o caso de uso fora de qualquer transação externa: cada lote tem a própria e
   * é confirmado antes do seguinte. Com lotes de dois e três vencidas, a falha na gravação do
   * evento da terceira, que está sozinha no segundo lote, desfaz só esse lote.
   */
  @Test
  @DisplayName("confirma cada lote antes do seguinte: a falha no segundo não desfaz o primeiro")
  void falhaNoSegundoLoteNaoDesfazOPrimeiro() {
    final var primeira = this.gravaPendenteHa(30);
    final var segunda = this.gravaPendenteHa(29);
    final var terceira = this.gravaPendenteHa(28);
    final BookingRoomResponsePublisher falhaNaTerceira = event -> {
      if (event.getReservationOrderId().equals(terceira.toString())) {
        throw new IllegalStateException("outbox down");
      }
      this.publisher.publish(event);
    };

    assertThatThrownBy(() -> this.schedulerComLotesDe(2, falhaNaTerceira).expirePendingBookings())
      .hasStackTraceContaining("outbox down");

    assertThat(this.statusOf(primeira)).isEqualTo("CANCELED");
    assertThat(this.statusOf(segunda)).isEqualTo("CANCELED");
    assertThat(this.statusOf(terceira)).isEqualTo("PENDING");
    assertThat(this.jdbc.sql("select count(*) from outbox_event").query(Long.class).single()).isEqualTo(2L);
  }

  /**
   * As reservas são gravadas da mais nova para a mais antiga, então a ordem física das linhas é
   * o contrário da ordem de criação: só o {@code order by created_at} da consulta devolve a mais
   * antiga primeiro.
   */
  @Test
  @DisplayName("devolve só as reservas vencidas mais antigas, na ordem de criação, até o limite do lote")
  void devolveAsMaisAntigasNaOrdemAteOLimite() {
    this.gravaPendenteHa(28);
    final var segunda = this.gravaPendenteHa(29);
    final var maisAntiga = this.gravaPendenteHa(30);

    final var lote = this.transaction.execute(status -> this.bookings.lockPendingCreatedBefore(Instant.now(), 2));

    assertThat(lote).extracting(Booking::getReservationOrderId).containsExactly(maisAntiga, segunda);
  }

  /**
   * O lote carrega as reservas e as linhas delas em número constante de consultas: antes, cada
   * reserva custava um {@code findById} e uma consulta lazy das linhas, com o lock do lote aberto.
   */
  @Test
  @DisplayName("carrega o lote em número constante de consultas, e não uma ou duas por reserva")
  void carregaOLoteSemUmaConsultaPorReserva() {
    final var ordens = new ArrayList<ReservationOrderId>();
    for (var minutos = 20; minutos < 25; minutos++) {
      ordens.add(this.gravaPendenteHa(minutos));
    }
    final var statistics = this.entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.setStatisticsEnabled(true);

    statistics.clear();
    final var lote = this.transaction.execute(status -> this.bookings.lockPendingCreatedBefore(Instant.now(), 10));
    final var consultasDoLote = statistics.getPrepareStatementCount();

    statistics.clear();
    this.transaction.execute(status -> this.bookings.lockByReservationOrderId(ordens.getFirst()).orElseThrow());
    final var consultasDoPedido = statistics.getPrepareStatementCount();

    assertThat(lote).hasSize(5);
    assertThat(consultasDoLote).as("consultas para travar e carregar 5 reservas").isLessThanOrEqualTo(3);
    assertThat(consultasDoPedido).as("consultas para travar e carregar a reserva do pedido").isLessThanOrEqualTo(3);
  }

  @Test
  @DisplayName("pagamento que chega depois da expiração não confirma a reserva")
  void pagamentoDepoisDaExpiracaoNaoConfirma() {
    final var order = this.gravaPendente(true);
    this.transaction.executeWithoutResult(status -> this.scheduler.expirePendingBookings());

    assertThatThrownBy(() -> this.transaction.executeWithoutResult(status -> {
      final var booking = this.bookings.lockByReservationOrderId(order).orElseThrow();
      booking.changeStatusTo(BookingStatus.CONFIRMED);
      this.bookings.save(booking);
    })).isInstanceOf(BookingDomainException.class);
    assertThat(this.statusOf(order)).isEqualTo("CANCELED");
  }

  @Test
  @DisplayName("o lock do pagamento impede a expiração de gravar por cima")
  void pagamentoComLockNaoDeixaAExpiracaoSobrescrever() throws Exception {
    final var order = this.gravaPendente(true);
    final var holding = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var worker = Executors.newSingleThreadExecutor();
    try {
      worker.submit(() -> this.transaction.executeWithoutResult(status -> {
        this.bookings.lockByReservationOrderId(order).orElseThrow();
        holding.countDown();
        try {
          assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (final InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(exception);
        }
        final var booking = this.bookings.lockByReservationOrderId(order).orElseThrow();
        booking.changeStatusTo(BookingStatus.CONFIRMED);
        this.bookings.save(booking);
      }));
      assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
      final var expiradas = this.transaction.execute(status ->
        this.bookings.lockPendingCreatedBefore(Instant.now(), 10)
      );
      assertThat(expiradas).isEmpty();
    } finally {
      release.countDown();
      worker.shutdown();
      assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(this.statusOf(order)).isEqualTo("CONFIRMED");
  }

  @Test
  @DisplayName("falha ao gravar o evento desfaz o cancelamento")
  void falhaAoPublicarDesfazOCancelamento() {
    final var order = this.gravaPendente(true);
    final var failing = new BookingExpirationScheduler(
      new ExpirePendingBookingsUseCaseImpl(this.bookings, Duration.ofMinutes(10)),
      event -> {
        throw new IllegalStateException("outbox down");
      },
      this.transactionManager,
      100
    );

    assertThatThrownBy(failing::expirePendingBookings).hasStackTraceContaining("outbox down");
    assertThat(this.statusOf(order)).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("duas instâncias não expiram a mesma reserva")
  void duasInstanciasNaoExpiramAMesmaReserva() throws Exception {
    this.gravaPendente(true);
    this.gravaPendente(true);
    final var holding = new CountDownLatch(2);
    final var release = new CountDownLatch(1);
    final var claimed = new ArrayList<String>();
    final var executor = Executors.newFixedThreadPool(2);
    try {
      for (var i = 0; i < 2; i++) {
        executor.submit(() -> this.transaction.executeWithoutResult(status -> {
          final var locked = this.bookings.lockPendingCreatedBefore(Instant.now(), 10);
          synchronized (claimed) {
            locked.forEach(booking -> claimed.add(booking.getId().toString()));
          }
          holding.countDown();
          try {
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
          } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
          }
          locked.forEach(booking -> {
            booking.changeStatusTo(BookingStatus.CANCELED);
            this.bookings.save(booking);
          });
        }));
      }
      assertThat(holding.await(5, TimeUnit.SECONDS))
        .as("a segunda instância esperou o lock da primeira; skip locked não segurou")
        .isTrue();
      assertThat(claimed).hasSize(2).doesNotHaveDuplicates();
    } finally {
      release.countDown();
      executor.shutdown();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(this.jdbc.sql("select count(*) from booking where check_in = :checkIn and status = 'CANCELED'")
      .param("checkIn", CHECK_IN).query(Long.class).single()).isEqualTo(2L);
  }

  private ReservationOrderId gravaPendente(final boolean vencida) {
    return vencida ? this.gravaPendenteHa(11) : this.gravaPendenteHa(0);
  }

  /** Grava a reserva {@code PENDING} com {@code created_at} há tantos minutos; zero deixa o instante da gravação. */
  private ReservationOrderId gravaPendenteHa(final int minutos) {
    final var order = ReservationOrderId.newInstance();
    final var pending = Booking.builder()
      .reservationOrderId(order)
      .customerId(CustomerId.newInstance())
      .bookingPeriod(BookingPeriod.of(CHECK_IN, CHECK_OUT))
      .totalPrice(Money.of(new BigDecimal("10.0000")))
      .bookingRooms(List.of(BookingRoom.builder().roomId(ROOM).quantity(1).price(Money.of(new BigDecimal("10.0000"))).build()))
      .build();
    pending.initialize();
    this.transaction.executeWithoutResult(status -> this.bookings.save(pending));
    if (minutos > 0) {
      this.transaction.executeWithoutResult(status -> this.jdbc.sql("""
        update booking set created_at = now() - (:minutos * interval '1 minute') where reservation_order_id = :order
        """).param("minutos", minutos).param("order", order.toString()).update());
    }
    return order;
  }

  private String customerOf(final ReservationOrderId order) {
    return this.jdbc.sql("select customer_id from booking where reservation_order_id = :order")
      .param("order", order.toString())
      .query(String.class)
      .single();
  }

  private Booking novaReserva() {
    final var booking = Booking.builder()
      .reservationOrderId(ReservationOrderId.newInstance())
      .customerId(CustomerId.newInstance())
      .bookingPeriod(BookingPeriod.of(CHECK_IN, CHECK_OUT))
      .totalPrice(Money.of(new BigDecimal("10.0000")))
      .bookingRooms(List.of(BookingRoom.builder().roomId(ROOM).quantity(1).price(Money.of(new BigDecimal("10.0000"))).build()))
      .build();
    booking.initialize();
    return booking;
  }

  private String statusOf(final ReservationOrderId order) {
    return this.jdbc.sql("select status from booking where reservation_order_id = :order")
      .param("order", order.toString())
      .query(String.class)
      .single();
  }

}
