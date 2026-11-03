package com.hotel.booking.system.customer.service;

import com.hotel.booking.system.commons.core.application.idempotency.IdempotentEventConsumer;
import com.hotel.booking.system.commons.core.domain.event.customer.*;
import com.hotel.booking.system.commons.infrastructure.messaging.idempotency.InMemoryProcessedEventRepository;
import com.hotel.booking.system.commons.core.domain.valueobject.*;
import com.hotel.booking.system.customer.service.infrastructure.configuration.*;
import com.hotel.booking.system.customer.service.application.service.impl.CustomerApplicationServiceImpl;
import com.hotel.booking.system.customer.service.application.web.controller.CustomerController;
import com.hotel.booking.system.customer.service.core.application.dto.*;
import com.hotel.booking.system.customer.service.core.application.mapper.CustomerUseCaseMapperImpl;
import com.hotel.booking.system.customer.service.core.application.usecase.*;
import com.hotel.booking.system.customer.service.core.domain.entity.*;
import com.hotel.booking.system.customer.service.core.domain.exception.*;
import com.hotel.booking.system.customer.service.core.domain.valueobject.*;
import com.hotel.booking.system.customer.service.core.ports.spi.repository.*;
import com.hotel.booking.system.customer.service.infrastructure.messaging.listener.CustomerBookingStatusUpdatedListenerImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Fluxo da projeção de reservas do cliente")
class CustomerFlowTest {
  private final CustomerBeanConfiguration beans = new CustomerBeanConfiguration();
  private final CustomerRepository customers = mock(CustomerRepository.class);
  private final ReservationOrderRepository orders = mock(ReservationOrderRepository.class);
  private final CustomerId customerId = CustomerId.newInstance();
  private final ReservationOrderId orderId = ReservationOrderId.newInstance();
  private final HotelId hotelId = HotelId.newInstance();
  private final IdempotentEventConsumer consumer = new IdempotentEventConsumer(new InMemoryProcessedEventRepository());

  @Test
  @DisplayName("declara transação de leitura no acesso HTTP e de escrita no listener")
  void declaraTransacaoDeLeituraNoAcessoHttpEDeEscritaNoListener() throws NoSuchMethodException {
    final var detail = CustomerApplicationServiceImpl.class
      .getMethod("getCustomerReservationOrderDetail", String.class, String.class)
      .getAnnotation(Transactional.class);
    final var listener = CustomerBookingStatusUpdatedListenerImpl.class
      .getMethod("listen", List.class)
      .getAnnotation(Transactional.class);

    assertThat(detail).isNotNull().extracting(Transactional::readOnly).isEqualTo(true);
    assertThat(listener).isNotNull().extracting(Transactional::readOnly).isEqualTo(false);
  }

  @Test
  @DisplayName("constrói a projeção e formata dinheiro, CPF e linha do tempo")
  void eventosConstroemProjecaoEConsultaFormataDinheiroCpfETimeline() {
    when(this.customers.customerExistsBy(this.customerId)).thenReturn(true);
    final var mapper = new CustomerUseCaseMapperImpl();
    final var handler = this.beans.customerBookingStatusUpdatedHandler(
      new InitializeCustomerBookingUseCaseImpl(this.customers, this.orders, mapper),
      new UpdateCustomerBookingStatusUseCaseImpl(this.orders),
      new UpdateCustomerBookingFailureStatusUseCaseImpl(this.orders), mapper);
    final var listener = new CustomerBookingStatusUpdatedListenerImpl(handler, this.consumer);
    listener.listen(List.of(this.initiated()));
    final var captured = ArgumentCaptor.forClass(ReservationOrder.class);
    verify(this.orders).save(captured.capture());
    final var order = captured.getValue();
    assertThat(order.getCustomerId()).isEqualTo(this.customerId);
    assertThat(order.getHotelId()).isEqualTo(this.hotelId);
    assertThat(order.getGuests()).isEqualTo(2);
    assertThat(order.getTotalPrice().getValue()).isEqualByComparingTo("199.9999");
    when(this.orders.findById(this.orderId)).thenReturn(order);
    listener.listen(List.of(CustomerBookingPaymentRequestedEvent.builder()
      .customerId(this.customerId.toString()).reservationOrderId(this.orderId.toString())
      .status(CustomerReservationStatus.AWAITING_PAYMENT).build()));
    assertThat(order.getCurrentStatus()).isEqualTo(CustomerReservationStatus.AWAITING_PAYMENT);
    listener.listen(List.of(CustomerBookingPaymentFailedEvent.builder()
      .customerId(this.customerId.toString()).reservationOrderId(this.orderId.toString())
      .status(CustomerReservationStatus.PAYMENT_FAILED).failureMessages(List.of("Recusado", "Sem saldo")).build()));
    assertThat(order.getCurrentStatus()).isEqualTo(CustomerReservationStatus.PAYMENT_FAILED);
    assertThat(order.getTimeline()).hasSize(3);
    final var customer = Customer.builder().id(this.customerId).name("Ana").cpf(new Cpf("01234567890")).build();
    when(this.customers.findById(this.customerId)).thenReturn(customer);
    final var service = new CustomerApplicationServiceImpl(
      new GetCustomerReservationOrderDetailImpl(this.customers, this.orders, mapper));
    final var response = new CustomerController(service).getCustomerReservationOrderDetail(
      this.customerId.toString(), this.orderId.toString());
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    final var output = service.getCustomerReservationOrderDetail(this.customerId.toString(), this.orderId.toString());
    assertThat(output.customerName()).isEqualTo("Ana");
    assertThat(output.customerCpf()).isEqualTo("012.345.678-90");
    assertThat(output.totalPrice()).isEqualByComparingTo("200.00");
    assertThat(output.checkIn()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(output.checkOut()).isEqualTo(LocalDate.of(2026, 10, 3));
    assertThat(output.reservationOrderId()).isEqualTo(this.orderId.toString());
    assertThat(output.hotelId()).isEqualTo(this.hotelId.toString());
    assertThat(output.customerId()).isEqualTo(this.customerId.toString());
    assertThat(output.status()).isEqualTo(CustomerReservationStatus.PAYMENT_FAILED);
    assertThat(output.timeline()).hasSize(3).anySatisfy(item ->
      assertThat(item.failureReason()).isEqualTo("Recusado\nSem saldo"));
    final var first = order.getTimeline().getFirst();
    assertThat(output.timeline()).anySatisfy(item -> assertThat(item.occurredAt())
      .isEqualTo(first.getOccurredAt().atOffset(ZoneOffset.ofHours(-4)).toLocalDateTime()));
  }

  @Test
  @DisplayName("impede a gravação quando o cliente não existe")
  void clienteAusenteImpedeGravacao() {
    final var mapper = new CustomerUseCaseMapperImpl();
    final var input = mapper.customerBookingInitiatedEventToInitializeCustomerBookingInput(this.initiated());
    assertThatThrownBy(() -> new InitializeCustomerBookingUseCaseImpl(this.customers, this.orders, mapper).execute(input))
      .isInstanceOf(CustomerNotFoundException.class);
    verifyNoInteractions(this.orders);
  }

  @Test
  @DisplayName("propaga falha do handler e aceita lote vazio")
  void listenerPropagaFalhaDoHandlerEAceitaLoteVazio() {
    final var handler = mock(com.hotel.booking.system.customer.service.core.ports.api.messaging.CustomerBookingStatusUpdatedHandler.class);
    final var event = this.initiated();
    doThrow(new IllegalStateException("falha de persistência")).when(handler).handle(event);
    final var listener = new CustomerBookingStatusUpdatedListenerImpl(handler, this.consumer);
    assertThatThrownBy(() -> listener.listen(List.of(event, this.initiated())))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Failed to process CustomerBookingStatusUpdatedEvent")
      .hasCauseInstanceOf(IllegalStateException.class);
    listener.listen(List.of());
    verify(handler, times(1)).handle(event);
    verifyNoMoreInteractions(handler);
  }

  @Test
  @DisplayName("processa uma só vez o evento repetido, no mesmo lote ou em outra entrega")
  void listenerDescartaEventoRepetido() {
    final var handler = mock(com.hotel.booking.system.customer.service.core.ports.api.messaging.CustomerBookingStatusUpdatedHandler.class);
    final var listener = new CustomerBookingStatusUpdatedListenerImpl(handler, this.consumer);
    final var event = this.initiated();
    listener.listen(List.of(event, event));
    listener.listen(List.of(event));
    verify(handler, times(1)).handle(event);
  }

  /**
   * Regra 3 da issue #7: a deduplicação por {@code eventId} não vê estes eventos como repetidos,
   * porque cada um tem o seu. É a projeção que os reconhece — inclusive a reentrega tardia do
   * evento inicial, que reescreveria o pedido de volta para {@code AWAITING_RESERVATION}.
   */
  @Test
  @DisplayName("não repete a transição que chega em outro evento, com eventId novo")
  void naoRepeteATransicaoQueChegaEmOutroEvento() {
    when(this.customers.customerExistsBy(this.customerId)).thenReturn(true);
    final var mapper = new CustomerUseCaseMapperImpl();
    final var handler = this.beans.customerBookingStatusUpdatedHandler(
      new InitializeCustomerBookingUseCaseImpl(this.customers, this.orders, mapper),
      new UpdateCustomerBookingStatusUseCaseImpl(this.orders),
      new UpdateCustomerBookingFailureStatusUseCaseImpl(this.orders), mapper);
    final var listener = new CustomerBookingStatusUpdatedListenerImpl(handler, this.consumer);
    listener.listen(List.of(this.initiated()));
    final var captured = ArgumentCaptor.forClass(ReservationOrder.class);
    verify(this.orders).save(captured.capture());
    final var order = captured.getValue();
    when(this.orders.findById(this.orderId)).thenReturn(order);
    when(this.orders.exists(this.orderId)).thenReturn(true);

    listener.listen(List.of(this.paymentRequested()));
    listener.listen(List.of(this.paymentRequested()));
    listener.listen(List.of(this.initiated()));

    assertThat(order.getTimeline()).extracting(ReservationOrderTimeline::getStatus)
      .containsExactly(CustomerReservationStatus.AWAITING_RESERVATION, CustomerReservationStatus.AWAITING_PAYMENT);
    assertThat(order.getCurrentStatus()).isEqualTo(CustomerReservationStatus.AWAITING_PAYMENT);
    verify(this.orders, times(2)).save(any(ReservationOrder.class));
  }

  private CustomerBookingPaymentRequestedEvent paymentRequested() {
    return CustomerBookingPaymentRequestedEvent.builder()
      .customerId(this.customerId.toString()).reservationOrderId(this.orderId.toString())
      .status(CustomerReservationStatus.AWAITING_PAYMENT).build();
  }

  private CustomerBookingInitiatedEvent initiated() {
    return CustomerBookingInitiatedEvent.builder().customerId(this.customerId.toString())
      .reservationOrderId(this.orderId.toString()).hotelId(this.hotelId.toString())
      .totalPrice(new BigDecimal("199.9999")).guests(2)
      .checkIn(LocalDate.of(2026, 10, 1)).checkOut(LocalDate.of(2026, 10, 3))
      .status(CustomerReservationStatus.AWAITING_RESERVATION).rooms(List.of()).build();
  }
}
