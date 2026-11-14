package com.hotel.booking.system.booking.service.infrastructure.db;

import com.hotel.booking.system.booking.service.application.scheduler.BookingExpirationScheduler;
import com.hotel.booking.system.booking.service.core.application.service.VerifyRoomAvailability;
import com.hotel.booking.system.booking.service.core.application.usecase.ExpirePendingBookingsUseCaseImpl;
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
import com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerId;
import com.hotel.booking.system.commons.core.domain.valueobject.Money;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.JacksonOutbox;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.JdbcOutboxRepository;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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

  private TransactionTemplate transaction;

  private BookingExpirationScheduler scheduler;

  @BeforeEach
  void prepara() {
    this.transaction = new TransactionTemplate(this.transactionManager);
    final var outbox = new JacksonOutbox(new JdbcOutboxRepository(this.jdbc), JsonMapper.builder().build());
    final var publisher = new BookingRoomResponsePublisherImpl(
      outbox,
      new RoutingKeyProperties("requested", "confirmation", "status"),
      new ExchangeProperties("exchange.booking-room")
    );
    final var useCase = new ExpirePendingBookingsUseCaseImpl(this.bookings, Duration.ofMinutes(10));
    this.scheduler = new BookingExpirationScheduler(useCase, publisher);
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

    this.transaction.executeWithoutResult(status -> this.scheduler.expirePendingBookings());

    assertThat(this.statusOf(order)).isEqualTo("CANCELED");
    final var payload = this.jdbc.sql("select event_type, payload::text from outbox_event").query((rs, row) ->
      rs.getString(1) + " " + rs.getString(2)
    ).list();
    assertThat(payload).singleElement().asString()
      .contains("BookingRoomExpiredEvent")
      .contains(BookingExpirationScheduler.EXPIRED_MESSAGE)
      .contains(order.toString());
    assertThat(this.jpa.findByRoomIdAndPeriod(ROOM.getValue(), CHECK_IN, CHECK_OUT)).isEmpty();

    final var novo = this.novaReserva();
    final var falhas = this.transaction.execute(status ->
      new VerifyRoomAvailability(this.bookings, this.rooms).execute(novo)
    );
    assertThat(falhas.data()).isEmpty();
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
          final var locked = this.bookings.lockPendingCreatedBefore(Instant.now());
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
    if (vencida) {
      this.transaction.executeWithoutResult(status -> this.jdbc.sql("""
        update booking set created_at = now() - interval '11 minutes' where reservation_order_id = :order
        """).param("order", order.toString()).update());
    }
    return order;
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
