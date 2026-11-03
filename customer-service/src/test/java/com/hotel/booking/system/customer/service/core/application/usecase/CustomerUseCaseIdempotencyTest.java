package com.hotel.booking.system.customer.service.core.application.usecase;

import com.hotel.booking.system.commons.core.domain.valueobject.CustomerId;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import com.hotel.booking.system.commons.core.domain.valueobject.FailureMessages;
import com.hotel.booking.system.commons.core.domain.valueobject.HotelId;
import com.hotel.booking.system.commons.core.domain.valueobject.Money;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.customer.service.core.application.dto.InitializeReservationOrderInput;
import com.hotel.booking.system.customer.service.core.application.dto.UpdateCustomerBookingFailureStatusInput;
import com.hotel.booking.system.customer.service.core.application.dto.UpdateCustomerBookingStatusInput;
import com.hotel.booking.system.customer.service.core.application.mapper.CustomerUseCaseMapperImpl;
import com.hotel.booking.system.customer.service.core.domain.entity.ReservationOrder;
import com.hotel.booking.system.customer.service.core.ports.spi.repository.CustomerRepository;
import com.hotel.booking.system.customer.service.core.ports.spi.repository.ReservationOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regra 3 da issue #7: além do controle por {@code eventId}, a projeção é naturalmente
 * idempotente. A mesma transição de status para o mesmo pedido não entra duas vezes na timeline
 * <em>mesmo com {@code eventId} novo</em> — a reentrega fora da janela de expurgo, ou uma
 * republicação que o Outbox (#35) ainda não impede —, e o caso de uso não grava o que não mudou.
 */
@DisplayName("Idempotência natural dos casos de uso da timeline")
class CustomerUseCaseIdempotencyTest {

  private final ReservationOrderRepository orders = mock(ReservationOrderRepository.class);
  private final CustomerRepository customers = mock(CustomerRepository.class);
  private final ReservationOrderId orderId = ReservationOrderId.newInstance();
  private final CustomerId customerId = CustomerId.newInstance();

  @Test
  @DisplayName("não grava de novo o status que o pedido já registrou por último")
  void naoGravaDeNovoOStatusJaRegistrado() {
    final var order = this.orderAt(CustomerReservationStatus.AWAITING_PAYMENT);
    when(this.orders.findById(this.orderId)).thenReturn(order);

    new UpdateCustomerBookingStatusUseCaseImpl(this.orders)
      .execute(this.statusInput(CustomerReservationStatus.AWAITING_PAYMENT));

    verify(this.orders, never()).save(any());
    assertThat(order.getTimeline()).hasSize(2);
  }

  @Test
  @DisplayName("grava o status novo, mesmo logo depois de um repetido")
  void gravaOStatusNovoMesmoDepoisDeUmRepetido() {
    final var order = this.orderAt(CustomerReservationStatus.AWAITING_PAYMENT);
    when(this.orders.findById(this.orderId)).thenReturn(order);
    final var useCase = new UpdateCustomerBookingStatusUseCaseImpl(this.orders);

    useCase.execute(this.statusInput(CustomerReservationStatus.AWAITING_PAYMENT));
    useCase.execute(this.statusInput(CustomerReservationStatus.PAYMENT_CONFIRMED));

    verify(this.orders, times(1)).save(order);
    assertThat(order.getCurrentStatus()).isEqualTo(CustomerReservationStatus.PAYMENT_CONFIRMED);
    assertThat(order.getTimeline()).hasSize(3);
  }

  @Test
  @DisplayName("não grava de novo a falha repetida, e grava a que traz outro motivo")
  void naoGravaDeNovoAFalhaRepetida() {
    final var order = this.orderAt(CustomerReservationStatus.AWAITING_PAYMENT);
    when(this.orders.findById(this.orderId)).thenReturn(order);
    final var useCase = new UpdateCustomerBookingFailureStatusUseCaseImpl(this.orders);

    useCase.execute(this.failureInput("Cartão recusado"));
    useCase.execute(this.failureInput("Cartão recusado"));
    verify(this.orders, times(1)).save(order);

    useCase.execute(this.failureInput("Saldo insuficiente"));
    verify(this.orders, times(2)).save(order);
    assertThat(order.getTimeline()).hasSize(4);
  }

  @Test
  @DisplayName("não reinicializa um pedido que já existe, e por isso não volta o status dele")
  void naoReinicializaPedidoExistente() {
    when(this.customers.customerExistsBy(this.customerId)).thenReturn(true);
    when(this.orders.exists(this.orderId)).thenReturn(true);

    new InitializeCustomerBookingUseCaseImpl(this.customers, this.orders, new CustomerUseCaseMapperImpl())
      .execute(this.initializeInput());

    verify(this.orders, never()).save(any());
  }

  @Test
  @DisplayName("inicializa o pedido que ainda não existe")
  void inicializaPedidoQueAindaNaoExiste() {
    when(this.orders.exists(this.orderId)).thenReturn(false);
    when(this.customers.customerExistsBy(this.customerId)).thenReturn(true);

    new InitializeCustomerBookingUseCaseImpl(this.customers, this.orders, new CustomerUseCaseMapperImpl())
      .execute(this.initializeInput());

    verify(this.orders, times(1)).save(any(ReservationOrder.class));
  }

  private ReservationOrder orderAt(final CustomerReservationStatus status) {
    final var order = ReservationOrder.builder()
      .id(this.orderId)
      .customerId(this.customerId)
      .hotelId(HotelId.newInstance())
      .totalPrice(Money.of(new BigDecimal("100")))
      .build();
    order.initialize();
    order.updateStatus(status);
    return order;
  }

  private UpdateCustomerBookingStatusInput statusInput(final CustomerReservationStatus status) {
    return new UpdateCustomerBookingStatusInput(this.customerId.toString(), this.orderId.toString(), status);
  }

  private UpdateCustomerBookingFailureStatusInput failureInput(final String reason) {
    return new UpdateCustomerBookingFailureStatusInput(
      this.customerId.toString(),
      this.orderId.toString(),
      CustomerReservationStatus.PAYMENT_FAILED,
      FailureMessages.newInstance(List.of(reason))
    );
  }

  private InitializeReservationOrderInput initializeInput() {
    return new InitializeReservationOrderInput(
      this.orderId.toString(),
      this.customerId.toString(),
      HotelId.newInstance().toString(),
      new BigDecimal("100"),
      2,
      LocalDate.of(2030, 1, 10),
      LocalDate.of(2030, 1, 13),
      CustomerReservationStatus.AWAITING_RESERVATION
    );
  }

}
