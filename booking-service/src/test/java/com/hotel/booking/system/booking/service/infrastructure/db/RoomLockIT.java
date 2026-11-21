package com.hotel.booking.system.booking.service.infrastructure.db;

import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.booking.service.core.domain.entity.BookingPeriod;
import com.hotel.booking.system.booking.service.core.domain.entity.BookingRoom;
import com.hotel.booking.system.booking.service.infrastructure.db.entity.RoomEntity;
import com.hotel.booking.system.booking.service.infrastructure.db.mapper.impl.BookingDatabaseMapperImpl;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.RoomJpaRepository;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters.BookingRepositoryAdapter;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters.RoomRepositoryAdapter;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerId;
import com.hotel.booking.system.commons.core.domain.valueobject.Money;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ordem dos locks e limite de espera (issue #56).
 */
@Import({BookingRepositoryAdapter.class, RoomRepositoryAdapter.class, BookingDatabaseMapperImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Lock dos quartos")
class RoomLockIT extends AbstractDatabaseIT {

  private static final LocalDate CHECK_IN = LocalDate.of(2043, 8, 1);

  @Autowired
  private RoomRepositoryAdapter rooms;

  @Autowired
  private RoomJpaRepository roomJpaRepository;

  @Autowired
  private BookingRepositoryAdapter bookings;

  @Autowired
  private BookingDatabaseMapperImpl mapper;

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private JdbcClient jdbc;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction;

  private UUID roomA;

  private UUID roomB;

  @BeforeEach
  void criaQuartos() {
    this.transaction = new TransactionTemplate(this.transactionManager);
    this.roomA = UUID.randomUUID();
    this.roomB = UUID.randomUUID();
    this.transaction.executeWithoutResult(status -> {
      this.persistRoom(this.roomA);
      this.persistRoom(this.roomB);
    });
  }

  @AfterEach
  void apaga() {
    this.transaction.executeWithoutResult(status -> {
      this.jdbc.sql("delete from booking_room where room_id in (:a, :b)")
        .param("a", this.roomA.toString()).param("b", this.roomB.toString()).update();
      this.jdbc.sql("delete from booking where check_in = :checkIn").param("checkIn", CHECK_IN).update();
      this.jdbc.sql("delete from room where id in (:a, :b)")
        .param("a", this.roomA.toString()).param("b", this.roomB.toString()).update();
    });
  }

  @Test
  @DisplayName("dois pedidos com os mesmos quartos em ordens opostas terminam sem deadlock")
  void ordensOpostasNaoEntramEmDeadlock() throws Exception {
    final var start = new CyclicBarrier(2);
    final var failures = new AtomicInteger();
    final var executor = Executors.newFixedThreadPool(2);
    try {
      executor.submit(() -> this.lockInOrder(start, failures, this.roomA, this.roomB));
      executor.submit(() -> this.lockInOrder(start, failures, this.roomB, this.roomA));
      executor.shutdown();
      assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    } finally {
      if (!executor.isShutdown()) {
        executor.shutdownNow();
      }
    }
    assertThat(failures).hasValue(0);
  }

  @Test
  @DisplayName("quem espera o lock além do limite falha sem gravar a reserva")
  void esperaAlemDoLimiteNaoGravaAReserva() throws Exception {
    final var holding = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var holder = Executors.newSingleThreadExecutor();
    holder.submit(() -> this.transaction.executeWithoutResult(status -> {
      this.rooms.lockAllByRoomId(List.of(RoomId.of(this.roomA)));
      holding.countDown();
      try {
        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
      } catch (final InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(exception);
      }
    }));
    assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
    final var impatient = new RoomRepositoryAdapter(
      this.roomJpaRepository,
      this.mapper,
      this.entityManager,
      Duration.ofMillis(300)
    );
    final var order = ReservationOrderId.newInstance();

    assertThatThrownBy(() -> this.transaction.executeWithoutResult(status -> {
      impatient.lockAllByRoomId(List.of(RoomId.of(this.roomA)));
      this.bookings.save(this.booking(order, this.roomA));
    })).hasStackTraceContaining("lock timeout");

    release.countDown();
    holder.shutdown();
    assertThat(holder.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    assertThat(this.jdbc.sql("select count(*) from booking where reservation_order_id = :order")
      .param("order", order.toString())
      .query(Long.class)
      .single()).isZero();
  }

  private void lockInOrder(final CyclicBarrier start, final AtomicInteger failures, final UUID first, final UUID second) {
    try {
      start.await(5, TimeUnit.SECONDS);
      this.transaction.executeWithoutResult(status ->
        this.rooms.lockAllByRoomId(List.of(RoomId.of(first), RoomId.of(second)))
      );
    } catch (final Exception exception) {
      failures.incrementAndGet();
    }
  }

  private void persistRoom(final UUID id) {
    final var room = new RoomEntity();
    room.setId(id);
    room.setHotelId(UUID.randomUUID());
    room.setCurrentPrice(new BigDecimal("10.0000"));
    room.setCapacity(2);
    room.setQuantity(4);
    this.entityManager.persist(room);
  }

  private Booking booking(final ReservationOrderId order, final UUID room) {
    final var booking = Booking.builder()
      .reservationOrderId(order)
      .customerId(CustomerId.newInstance())
      .bookingPeriod(BookingPeriod.of(CHECK_IN, CHECK_IN.plusDays(2)))
      .totalPrice(Money.of(new BigDecimal("10.0000")))
      .bookingRooms(List.of(BookingRoom.builder()
        .roomId(RoomId.of(room))
        .quantity(1)
        .price(Money.of(new BigDecimal("10.0000")))
        .build()))
      .build();
    booking.initialize();
    return booking;
  }

}
