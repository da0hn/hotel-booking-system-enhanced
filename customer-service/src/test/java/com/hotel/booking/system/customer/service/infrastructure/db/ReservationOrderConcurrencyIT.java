package com.hotel.booking.system.customer.service.infrastructure.db;

import com.hotel.booking.system.commons.core.domain.valueobject.CustomerId;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import com.hotel.booking.system.commons.core.domain.valueobject.HotelId;
import com.hotel.booking.system.commons.core.domain.valueobject.Money;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.customer.service.core.domain.entity.ReservationOrder;
import com.hotel.booking.system.customer.service.core.domain.entity.ReservationOrderTimeline;
import com.hotel.booking.system.customer.service.infrastructure.db.mapper.impl.CustomerDatabaseMapperImpl;
import com.hotel.booking.system.customer.service.infrastructure.db.repository.adapters.CustomerRepositoryAdapter;
import com.hotel.booking.system.customer.service.infrastructure.db.repository.adapters.ReservationOrderRepositoryAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Duas transações do mesmo pedido, as duas lendo antes de qualquer commit (issue #53).
 */
@Import({CustomerDatabaseMapperImpl.class, CustomerRepositoryAdapter.class, ReservationOrderRepositoryAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Corrida do status atual do pedido")
class ReservationOrderConcurrencyIT extends AbstractDatabaseIT {

  private static final CustomerId CLIENTE = CustomerId.of("f1e28a47-8852-45e8-b8e1-e6701633dd56");

  @Autowired
  private ReservationOrderRepositoryAdapter orders;

  @Autowired
  private JdbcClient jdbc;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction;

  private ReservationOrderId orderId;

  @BeforeEach
  void gravaPedido() {
    this.transaction = new TransactionTemplate(this.transactionManager);
    this.orderId = ReservationOrderId.newInstance();
    final var order = ReservationOrder.builder()
      .id(this.orderId)
      .customerId(CLIENTE)
      .hotelId(HotelId.newInstance())
      .guests(2)
      .checkIn(LocalDate.of(2042, 1, 1))
      .checkOut(LocalDate.of(2042, 1, 3))
      .totalPrice(Money.of(new BigDecimal("10.0000")))
      .build();
    order.initialize();
    this.transaction.executeWithoutResult(status -> this.orders.save(order));
  }

  @AfterEach
  void apagaPedido() {
    this.transaction.executeWithoutResult(status -> {
      this.jdbc.sql("delete from reservation_order_history where reservation_order_id = :id")
        .param("id", this.orderId.toString()).update();
      this.jdbc.sql("delete from reservation_order where id = :id")
        .param("id", this.orderId.toString()).update();
    });
  }

  @Test
  @DisplayName("a transação que perde a corrida falha, e o retry deixa o status igual ao da última entrada")
  void corridaDoMesmoPedidoFalhaEOretryConverge() throws Exception {
    final var bothRead = new CyclicBarrier(2);
    final var bothWrote = new CyclicBarrier(2);
    final var failures = new AtomicInteger();
    final var statuses = List.of(
      CustomerReservationStatus.AWAITING_PAYMENT,
      CustomerReservationStatus.PAYMENT_CONFIRMED
    );
    final var executor = Executors.newFixedThreadPool(2);
    try {
      for (final var status : statuses) {
        executor.submit(() -> {
          try {
            this.transaction.executeWithoutResult(tx -> {
              final var order = this.orders.findById(this.orderId);
              this.await(bothRead);
              order.updateStatus(status);
              this.orders.save(order);
              this.await(bothWrote);
            });
          } catch (final OptimisticLockingFailureException exception) {
            failures.incrementAndGet();
          }
        });
      }
      executor.shutdown();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      if (!executor.isShutdown()) {
        executor.shutdownNow();
      }
    }

    assertThat(failures).hasValue(1);
    final var afterRace = this.transaction.execute(tx -> this.orders.findById(this.orderId));
    assertThat(afterRace.getTimeline()).hasSize(2);
    assertThat(afterRace.getCurrentStatus()).isEqualTo(this.latest(afterRace));

    final var missing = statuses.stream()
      .filter(status -> afterRace.getTimeline().stream().noneMatch(entry -> entry.getStatus() == status))
      .findFirst()
      .orElseThrow();
    this.transaction.executeWithoutResult(tx -> {
      final var order = this.orders.findById(this.orderId);
      assertThat(order.updateStatus(missing)).isTrue();
      this.orders.save(order);
    });

    final var stored = this.transaction.execute(tx -> this.orders.findById(this.orderId));
    assertThat(stored.getTimeline()).hasSize(3);
    assertThat(stored.getTimeline().stream().map(ReservationOrderTimeline::getStatus))
      .contains(CustomerReservationStatus.AWAITING_RESERVATION, CustomerReservationStatus.AWAITING_PAYMENT, CustomerReservationStatus.PAYMENT_CONFIRMED);
    assertThat(stored.getCurrentStatus()).isEqualTo(this.latest(stored));
  }

  @Test
  @DisplayName("pedidos diferentes seguem em paralelo")
  void pedidosDiferentesSeguemEmParalelo() throws Exception {
    final var otherId = ReservationOrderId.newInstance();
    final var other = ReservationOrder.builder()
      .id(otherId)
      .customerId(CLIENTE)
      .hotelId(HotelId.newInstance())
      .guests(1)
      .checkIn(LocalDate.of(2042, 2, 1))
      .checkOut(LocalDate.of(2042, 2, 3))
      .totalPrice(Money.of(new BigDecimal("10.0000")))
      .build();
    other.initialize();
    this.transaction.executeWithoutResult(status -> this.orders.save(other));
    final var bothRead = new CyclicBarrier(2);
    final var executor = Executors.newFixedThreadPool(2);
    try {
      executor.submit(() -> this.transaction.executeWithoutResult(tx -> {
        final var order = this.orders.findById(this.orderId);
        this.await(bothRead);
        order.updateStatus(CustomerReservationStatus.AWAITING_PAYMENT);
        this.orders.save(order);
      }));
      executor.submit(() -> this.transaction.executeWithoutResult(tx -> {
        final var order = this.orders.findById(otherId);
        this.await(bothRead);
        order.updateStatus(CustomerReservationStatus.PAYMENT_CONFIRMED);
        this.orders.save(order);
      }));
      executor.shutdown();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      final var first = this.transaction.execute(tx -> this.orders.findById(this.orderId));
      final var second = this.transaction.execute(tx -> this.orders.findById(otherId));
      assertThat(first.getCurrentStatus()).isEqualTo(CustomerReservationStatus.AWAITING_PAYMENT);
      assertThat(second.getCurrentStatus()).isEqualTo(CustomerReservationStatus.PAYMENT_CONFIRMED);
    } finally {
      this.transaction.executeWithoutResult(status -> {
        this.jdbc.sql("delete from reservation_order_history where reservation_order_id = :id")
          .param("id", otherId.toString()).update();
        this.jdbc.sql("delete from reservation_order where id = :id")
          .param("id", otherId.toString()).update();
      });
    }
  }

  private CustomerReservationStatus latest(final ReservationOrder order) {
    return order.getTimeline().latest().orElseThrow().getStatus();
  }

  private void await(final CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
    } catch (final Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

}
