package com.hotel.booking.system.hotel.service.core.application.messaging;

import com.hotel.booking.system.commons.core.domain.event.*;
import com.hotel.booking.system.commons.core.domain.event.customer.*;
import com.hotel.booking.system.commons.core.domain.valueobject.*;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Respostas da saga de reservas")
class SagaResponseHandlersTest {
  private final CustomerBookingRoomStatusUpdatedPublisher customer = mock(CustomerBookingRoomStatusUpdatedPublisher.class);
  private final PaymentRequestedPublisher payment = mock(PaymentRequestedPublisher.class);
  private final BookingRoomStatusChangedPublisher booking = mock(BookingRoomStatusChangedPublisher.class);

  @Test
  @DisplayName("notifica o cliente e solicita pagamento para uma reserva pendente")
  void reservaPendenteNotificaClienteAntesDeCobrarSemArredondar() {
    final var event = BookingRoomPendingEvent.builder().reservationOrderId("order").customerId("customer")
      .bookingRoomId("booking").totalPrice(new BigDecimal("199.9999")).build();
    new com.hotel.booking.system.hotel.service.infrastructure.configuration.HotelBeanConfiguration().bookingRoomResponseHandler(this.customer, this.payment).handle(event);
    final var updates = ArgumentCaptor.forClass(CustomerBookingStatusUpdatedEvent.class);
    final var payments = ArgumentCaptor.forClass(PaymentRequestedEvent.class);
    final var order = inOrder(this.customer, this.payment);
    order.verify(this.customer).publish(updates.capture());
    order.verify(this.payment).publish(payments.capture());
    final var update = (CustomerBookingPaymentRequestedEvent) updates.getValue();
    assertThat(update.getStatus()).isEqualTo(CustomerReservationStatus.AWAITING_PAYMENT);
    assertThat(update.getBookingRoomId()).isEqualTo("booking");
    assertThat(update.getReservationOrderId()).isEqualTo("order");
    assertThat(update.getCustomerId()).isEqualTo("customer");
    assertThat(payments.getValue()).extracting(PaymentRequestedEvent::getReservationOrderId,
      PaymentRequestedEvent::getCustomerId, PaymentRequestedEvent::getBookingRoomId, PaymentRequestedEvent::getTotalPrice)
      .containsExactly("order", "customer", "booking", new BigDecimal("199.9999"));
    verifyNoMoreInteractions(this.customer, this.payment);
  }

  @Test
  @DisplayName("conclui a projeção quando a reserva é confirmada")
  void reservaConfirmadaConcluiProjecaoSemCobrarNovamente() {
    new com.hotel.booking.system.hotel.service.infrastructure.configuration.HotelBeanConfiguration().bookingRoomResponseHandler(this.customer, this.payment).handle(
      BookingRoomConfirmedEvent.builder().reservationOrderId("order").customerId("customer").build());
    final var capture = ArgumentCaptor.forClass(CustomerBookingStatusUpdatedEvent.class);
    verify(this.customer).publish(capture.capture());
    assertThat(capture.getValue()).isInstanceOf(CustomerBookingCompletedEvent.class);
    assertThat(capture.getValue().getStatus()).isEqualTo(CustomerReservationStatus.RESERVED);
    assertThat(capture.getValue().getReservationOrderId()).isEqualTo("order");
    assertThat(capture.getValue().getCustomerId()).isEqualTo("customer");
    verifyNoInteractions(this.payment);
  }

  @Test
  @DisplayName("preserva os motivos ao rejeitar uma reserva")
  void reservaRejeitadaPreservaMotivosSemPedirPagamento() {
    final var reasons = List.of("sem disponibilidade", "capacidade insuficiente");
    new com.hotel.booking.system.hotel.service.infrastructure.configuration.HotelBeanConfiguration().bookingRoomResponseHandler(this.customer, this.payment).handle(
      BookingRoomFailedEvent.builder().reservationOrderId("order").customerId("customer").failureMessages(reasons).build());
    final var capture = ArgumentCaptor.forClass(CustomerBookingStatusUpdatedEvent.class);
    verify(this.customer).publish(capture.capture());
    final var event = (CustomerBookingRejectedEvent) capture.getValue();
    assertThat(event.getFailureMessages()).containsExactlyElementsOf(reasons);
    assertThat(event.getStatus()).isEqualTo(CustomerReservationStatus.RESERVATION_FAILED);
    assertThat(event.getCustomerId()).isEqualTo("customer");
    assertThat(event.getReservationOrderId()).isEqualTo("order");
    verifyNoInteractions(this.payment);
  }

  @Test
  @DisplayName("confirma a reserva depois de notificar pagamento concluído")
  void pagamentoConcluidoConfirmaReservaDepoisDeNotificarCliente() {
    new com.hotel.booking.system.hotel.service.infrastructure.configuration.HotelBeanConfiguration().paymentResponseHandler(this.customer, this.booking).handle(
      PaymentCompletedEvent.builder().reservationOrderId("order").customerId("customer").build());
    final var customers = ArgumentCaptor.forClass(CustomerBookingStatusUpdatedEvent.class);
    final var bookings = ArgumentCaptor.forClass(BookingRoomStatusUpdatedEvent.class);
    final var order = inOrder(this.customer, this.booking);
    order.verify(this.customer).publish(customers.capture());
    order.verify(this.booking).publish(bookings.capture());
    assertThat(customers.getValue()).isInstanceOf(CustomerBookingPaymentCompletedEvent.class);
    assertThat(customers.getValue().getStatus()).isEqualTo(CustomerReservationStatus.PAYMENT_CONFIRMED);
    assertThat(customers.getValue().getCustomerId()).isEqualTo("customer");
    assertThat(customers.getValue().getReservationOrderId()).isEqualTo("order");
    assertThat(bookings.getValue()).isInstanceOf(BookingRoomPaymentCompleted.class);
    assertThat(bookings.getValue().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(bookings.getValue().getCustomerId()).isEqualTo("customer");
    assertThat(bookings.getValue().getReservationOrderId()).isEqualTo("order");
  }

  @Test
  @DisplayName("cancela a reserva e preserva os motivos quando o pagamento falha")
  void pagamentoRecusadoCancelaReservaEPreservaMotivosNasDuasPontas() {
    final var reasons = List.of("saldo insuficiente", "pagamento recusado");
    new com.hotel.booking.system.hotel.service.infrastructure.configuration.HotelBeanConfiguration().paymentResponseHandler(this.customer, this.booking).handle(
      PaymentFailedEvent.builder().reservationOrderId("order").customerId("customer").failureMessages(reasons).build());
    final var customers = ArgumentCaptor.forClass(CustomerBookingStatusUpdatedEvent.class);
    final var bookings = ArgumentCaptor.forClass(BookingRoomStatusUpdatedEvent.class);
    final var order = inOrder(this.customer, this.booking);
    order.verify(this.customer).publish(customers.capture());
    order.verify(this.booking).publish(bookings.capture());
    final var customerEvent = (CustomerBookingPaymentFailedEvent) customers.getValue();
    final var bookingEvent = (BookingRoomPaymentFailed) bookings.getValue();
    assertThat(customerEvent.getFailureMessages()).containsExactlyElementsOf(reasons);
    assertThat(customerEvent.getStatus()).isEqualTo(CustomerReservationStatus.PAYMENT_FAILED);
    assertThat(customerEvent.getReservationOrderId()).isEqualTo("order");
    assertThat(customerEvent.getCustomerId()).isEqualTo("customer");
    assertThat(bookingEvent.getFailureMessages()).containsExactlyElementsOf(reasons);
    assertThat(bookingEvent.getStatus()).isEqualTo(BookingStatus.CANCELED);
    assertThat(bookingEvent.getReservationOrderId()).isEqualTo("order");
    assertThat(bookingEvent.getCustomerId()).isEqualTo("customer");
  }
}
