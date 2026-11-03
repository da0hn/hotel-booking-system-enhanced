package com.hotel.booking.system.customer.service.infrastructure.db;

import com.hotel.booking.system.commons.core.domain.valueobject.*;
import com.hotel.booking.system.customer.service.core.application.dto.InitializeReservationOrderInput;
import com.hotel.booking.system.customer.service.core.application.dto.UpdateCustomerBookingFailureStatusInput;
import com.hotel.booking.system.customer.service.core.application.dto.UpdateCustomerBookingStatusInput;
import com.hotel.booking.system.customer.service.core.application.mapper.CustomerUseCaseMapperImpl;
import com.hotel.booking.system.customer.service.core.application.usecase.InitializeCustomerBookingUseCaseImpl;
import com.hotel.booking.system.customer.service.core.application.usecase.UpdateCustomerBookingFailureStatusUseCaseImpl;
import com.hotel.booking.system.customer.service.core.application.usecase.UpdateCustomerBookingStatusUseCaseImpl;
import com.hotel.booking.system.customer.service.core.domain.entity.*;
import com.hotel.booking.system.customer.service.core.domain.exception.*;
import com.hotel.booking.system.customer.service.infrastructure.db.entity.ReservationOrderEntity;
import com.hotel.booking.system.customer.service.infrastructure.db.mapper.impl.CustomerDatabaseMapperImpl;
import com.hotel.booking.system.customer.service.infrastructure.db.repository.adapters.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Import({CustomerDatabaseMapperImpl.class, CustomerRepositoryAdapter.class, ReservationOrderRepositoryAdapter.class})
@DisplayName("Adaptadores de repositório de clientes")
class CustomerRepositoryAdaptersIT extends AbstractDatabaseIT {
  @Autowired private CustomerRepositoryAdapter customers;
  @Autowired private ReservationOrderRepositoryAdapter orders;
  @Autowired private EntityManager entityManager;
  private static final CustomerId CLIENTE = CustomerId.of("f1e28a47-8852-45e8-b8e1-e6701633dd56");

  @Test
  @DisplayName("consulta cliente do seed e traduz ausências")
  void adapterConsultaClienteDoSeedETraduzAusencia() {
    assertThat(this.customers.customerExistsBy(CLIENTE)).isTrue();
    assertThat(this.customers.findById(CLIENTE).getName()).isEqualTo("Gabriel Honda");
    assertThat(this.customers.findCustomerEntityById(CLIENTE).getId()).isEqualTo(CLIENTE.getValue());
    final var missing = CustomerId.newInstance();
    assertThat(this.customers.customerExistsBy(missing)).isFalse();
    assertThatThrownBy(() -> this.customers.findById(missing)).isInstanceOf(CustomerNotFoundException.class);
    assertThatThrownBy(() -> this.customers.findCustomerEntityById(missing)).isInstanceOf(CustomerNotFoundException.class);
    assertThatThrownBy(() -> this.orders.findById(ReservationOrderId.newInstance()))
      .isInstanceOf(ReservationOrderNotFoundException.class);
  }

  @Test
  @DisplayName("persiste a projeção completa e adiciona falha ao histórico")
  void adapterPersisteProjecaoCompletaEAcrescentaFalhaSemPerderHistorico() {
    final var order = ReservationOrder.builder().id(ReservationOrderId.newInstance()).customerId(CLIENTE)
      .hotelId(HotelId.newInstance()).guests(3).checkIn(LocalDate.of(2027, 1, 1))
      .checkOut(LocalDate.of(2027, 1, 3)).totalPrice(Money.of(new BigDecimal("1234.5678"))).build();
    order.initialize();
    this.orders.save(order);
    this.entityManager.flush();
    this.entityManager.clear();
    final var restored = this.orders.findById(order.getId());
    assertThat(restored.getCustomerId()).isEqualTo(CLIENTE);
    assertThat(restored.getHotelId()).isEqualTo(order.getHotelId());
    assertThat(restored.getGuests()).isEqualTo(3);
    assertThat(restored.getCheckIn()).isEqualTo(order.getCheckIn());
    assertThat(restored.getCheckOut()).isEqualTo(order.getCheckOut());
    assertThat(restored.getTotalPrice().getValue()).isEqualByComparingTo("1234.5678");
    assertThat(restored.getTimeline()).hasSize(1);
    restored.updateToFailureStatus(CustomerReservationStatus.RESERVATION_FAILED,
      FailureMessages.newInstance(List.of("Quarto indisponível", "Reserva concorrente")));
    this.orders.save(restored);
    this.entityManager.flush();
    this.entityManager.clear();
    final var failed = this.orders.findById(order.getId());
    assertThat(failed.getCurrentStatus()).isEqualTo(CustomerReservationStatus.RESERVATION_FAILED);
    assertThat(failed.getTimeline()).hasSize(2).extracting(ReservationOrderTimeline::getReason)
      .contains("Quarto indisponível\nReserva concorrente");
  }

  /**
   * Regra 3 da issue #7 contra o PostgreSQL de verdade, pelos adaptadores e casos de uso reais: a
   * mesma transição aplicada de novo — com {@code eventId} novo, que é o que a deduplicação por
   * evento não enxerga — não deixa uma segunda linha em {@code reservation_order_history}. A
   * última chamada é a reentrega tardia do evento inicial, que sem a regra reescreveria o pedido
   * de volta para {@code AWAITING_RESERVATION}.
   */
  @Test
  @DisplayName("deixa uma só linha no histórico quando a mesma transição é aplicada de novo")
  void mesmaTransicaoAplicadaDeNovoDeixaUmaSoLinhaNoHistorico() {
    final var orderId = ReservationOrderId.newInstance();
    final var initialize = new InitializeCustomerBookingUseCaseImpl(
      this.customers, this.orders, new CustomerUseCaseMapperImpl());
    final var update = new UpdateCustomerBookingStatusUseCaseImpl(this.orders);
    final var fail = new UpdateCustomerBookingFailureStatusUseCaseImpl(this.orders);
    final var initial = new InitializeReservationOrderInput(orderId.toString(), CLIENTE.toString(),
      HotelId.newInstance().toString(), new BigDecimal("100"), 2,
      LocalDate.of(2030, 1, 10), LocalDate.of(2030, 1, 13), CustomerReservationStatus.AWAITING_RESERVATION);
    final var awaitingPayment = new UpdateCustomerBookingStatusInput(
      CLIENTE.toString(), orderId.toString(), CustomerReservationStatus.AWAITING_PAYMENT);
    final var refused = new UpdateCustomerBookingFailureStatusInput(CLIENTE.toString(), orderId.toString(),
      CustomerReservationStatus.PAYMENT_FAILED, FailureMessages.newInstance(List.of("Cartão recusado")));

    initialize.execute(initial);
    this.sync();
    update.execute(awaitingPayment);
    this.sync();
    update.execute(awaitingPayment);
    this.sync();
    initialize.execute(initial);
    this.sync();
    assertThat(this.historyRows(orderId)).as("inicial + AWAITING_PAYMENT, sem repetição").isEqualTo(2);
    assertThat(this.orders.findById(orderId).getCurrentStatus())
      .as("a reentrega do evento inicial não pode desfazer o andamento do pedido")
      .isEqualTo(CustomerReservationStatus.AWAITING_PAYMENT);

    fail.execute(refused);
    this.sync();
    fail.execute(refused);
    this.sync();
    assertThat(this.historyRows(orderId)).as("a falha repetida também entra uma vez só").isEqualTo(3);
    assertThat(this.orders.findById(orderId).getCurrentStatus()).isEqualTo(CustomerReservationStatus.PAYMENT_FAILED);
  }

  private void sync() {
    this.entityManager.flush();
    this.entityManager.clear();
  }

  private long historyRows(final ReservationOrderId orderId) {
    return ((Number) this.entityManager
      .createNativeQuery("select count(*) from reservation_order_history where reservation_order_id = :id")
      .setParameter("id", orderId.toString())
      .getSingleResult()).longValue();
  }

  @Test
  @DisplayName("deduplica entidades de persistência pelo identificador do pedido")
  void identidadeDaEntidadePermiteDeduplicacaoPorPedido() {
    final var uuid = UUID.randomUUID();
    final var first = ReservationOrderEntity.builder().id(uuid).build();
    final var same = ReservationOrderEntity.builder().id(uuid).build();
    assertThat(first.equals(first)).isTrue();
    assertThat(first.equals(null)).isFalse();
    assertThat(first.equals("other type")).isFalse();
    assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
    assertThat(first).isNotEqualTo(ReservationOrderEntity.builder().id(UUID.randomUUID()).build());
  }
}
