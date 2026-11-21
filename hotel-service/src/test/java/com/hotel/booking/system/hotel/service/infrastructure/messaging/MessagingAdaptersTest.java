package com.hotel.booking.system.hotel.service.infrastructure.messaging;

import com.hotel.booking.system.commons.core.application.idempotency.IdempotentEventConsumer;
import com.hotel.booking.system.commons.core.application.outbox.Outbox;
import com.hotel.booking.system.commons.core.domain.event.*;
import com.hotel.booking.system.commons.infrastructure.messaging.idempotency.InMemoryProcessedEventRepository;
import com.hotel.booking.system.commons.core.domain.event.customer.*;
import com.hotel.booking.system.hotel.service.core.ports.api.messaging.*;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.*;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.listener.*;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.publisher.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Adaptadores de mensageria do serviço de hotéis")
class MessagingAdaptersTest {
  private final Outbox outbox = mock(Outbox.class);
  private final IdempotentEventConsumer consumer = new IdempotentEventConsumer(new InMemoryProcessedEventRepository());
  private final ExchangeProperties exchanges = new ExchangeProperties("booking", "payment", "customer");
  private final RoutingKeyProperties routes = new RoutingKeyProperties("request", "confirmation", "status", "charge", "paid", "update");

  @Test
  @DisplayName("grava o pedido de reserva no outbox, no destino configurado")
  void publicaPedidoDeReservaNoDestinoConfigurado() {
    final var event = BookingRoomRequestedEvent.builder().reservationOrderId("order").build();
    this.verifyPublisher(event, new BookingRoomRequestedPublisherImpl(this.outbox, this.routes, this.exchanges)::publish, "booking", "request");
  }

  @Test
  @DisplayName("grava a mudança de reserva no outbox, no destino configurado")
  void publicaMudancaDeReservaNoDestinoConfigurado() {
    final var event = BookingRoomPaymentCompleted.builder().reservationOrderId("order").build();
    this.verifyPublisher(event, new BookingRoomStatusChangedPublisherImpl(this.outbox, this.routes, this.exchanges)::publish, "booking", "status");
  }

  @Test
  @DisplayName("grava a atualização do cliente no outbox, no destino configurado")
  void publicaAtualizacaoDoClienteNoDestinoConfigurado() {
    final var event = CustomerBookingCompletedEvent.builder().reservationOrderId("order").build();
    this.verifyPublisher(event, new CustomerBookingRoomStatusUpdatedPublisherImpl(this.outbox, this.routes, this.exchanges)::publish, "customer", "update");
  }

  @Test
  @DisplayName("grava o pedido de pagamento no outbox, no destino configurado")
  void publicaPedidoDePagamentoNoDestinoConfigurado() {
    final var event = PaymentRequestedEvent.builder().reservationOrderId("order").build();
    this.verifyPublisher(event, new PaymentRequestedPublisherImpl(this.outbox, this.routes, this.exchanges)::publish, "payment", "charge");
  }

  /**
   * O publicador não fala com o broker: grava a linha com o destino já resolvido. A falha de
   * gravar sobe, para a transação de quem chamou desfazer o efeito junto com a linha.
   */
  private <T> void verifyPublisher(final T event, final Consumer<T> publisher, final String exchange, final String route) {
    publisher.accept(event);
    verify(this.outbox).append("order", exchange, route, event);
    final var failure = new IllegalStateException("banco indisponível");
    doThrow(failure).when(this.outbox).append(any(), any(), any(), any());
    assertThatThrownBy(() -> publisher.accept(event)).isSameAs(failure);
  }

  @Test
  @DisplayName("encaminha a resposta de reserva e propaga falha do handler")
  void listenerDeReservaEncaminhaMesmaMensagemEPropagaFalhaDoHandler() {
    final var handler = mock(BookingRoomResponseHandler.class);
    final var listener = new BookingRoomResponseListenerImpl(handler, this.consumer);
    final var event = BookingRoomConfirmedEvent.builder().reservationOrderId("order").build();
    listener.listen(event);
    verify(handler).handle(event);
    final var failure = new IllegalStateException("handler failed");
    final var other = BookingRoomConfirmedEvent.builder().reservationOrderId("other").build();
    doThrow(failure).when(handler).handle(other);
    assertThatThrownBy(() -> listener.listen(other)).isSameAs(failure);
  }

  /**
   * A reentrega de uma resposta do booking ou do payment não pode gerar uma segunda mensagem
   * adiante: o que o hotel publica leva um {@code eventId} novo, e o próximo serviço não teria
   * como reconhecê-la como repetida.
   */
  @Test
  @DisplayName("descarta a reentrega das respostas sem chamar o handler de novo")
  void listenersDescartamReentrega() {
    final var bookingHandler = mock(BookingRoomResponseHandler.class);
    final var paymentHandler = mock(PaymentResponseHandler.class);
    final var bookingListener = new BookingRoomResponseListenerImpl(bookingHandler, this.consumer);
    final var paymentListener = new PaymentResponseListenerImpl(paymentHandler, this.consumer);
    final var booking = BookingRoomConfirmedEvent.builder().reservationOrderId("order").build();
    final var payment = PaymentCompletedEvent.builder().reservationOrderId("order").build();
    bookingListener.listen(booking);
    bookingListener.listen(booking);
    paymentListener.listen(payment);
    paymentListener.listen(payment);
    verify(bookingHandler, times(1)).handle(booking);
    verify(paymentHandler, times(1)).handle(payment);
  }

  @Test
  @DisplayName("declara transação nos listeners para que o registro do eventId acompanhe o efeito")
  void listenersDeclaramTransacao() throws NoSuchMethodException {
    assertThat(BookingRoomResponseListenerImpl.class.getMethod("listen", BookingRoomResponseEvent.class)
      .getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull();
    assertThat(PaymentResponseListenerImpl.class.getMethod("listen", PaymentResponseEvent.class)
      .getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull();
  }

  @Test
  @DisplayName("encaminha a resposta de pagamento e propaga falha do handler")
  void listenerDePagamentoEncaminhaMesmaMensagemEPropagaFalhaDoHandler() {
    final var handler = mock(PaymentResponseHandler.class);
    final var listener = new PaymentResponseListenerImpl(handler, this.consumer);
    final var event = PaymentCompletedEvent.builder().reservationOrderId("order").build();
    listener.listen(event);
    verify(handler).handle(event);
    final var failure = new IllegalStateException("handler failed");
    final var other = PaymentCompletedEvent.builder().reservationOrderId("other").build();
    doThrow(failure).when(handler).handle(other);
    assertThatThrownBy(() -> listener.listen(other)).isSameAs(failure);
  }
}
