package com.hotel.booking.system.hotel.service.infrastructure.messaging;

import com.hotel.booking.system.commons.core.application.exception.MessagePublishException;
import com.hotel.booking.system.commons.core.domain.event.*;
import com.hotel.booking.system.commons.core.domain.event.customer.*;
import com.hotel.booking.system.hotel.service.core.ports.api.messaging.*;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.*;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.listener.*;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.publisher.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Adaptadores de mensageria do serviço de hotéis")
class MessagingAdaptersTest {
  private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
  private final ExchangeProperties exchanges = new ExchangeProperties("booking", "payment", "customer");
  private final RoutingKeyProperties routes = new RoutingKeyProperties("request", "confirmation", "status", "charge", "paid", "update");

  @Test
  @DisplayName("publica pedido de reserva no destino configurado")
  void publicaPedidoDeReservaNoDestinoConfigurado() {
    final var event = BookingRoomRequestedEvent.builder().reservationOrderId("order").build();
    this.verifyPublisher(event, new BookingRoomRequestedPublisherImpl(this.rabbit, this.routes, this.exchanges)::publish, "booking", "request");
  }

  @Test
  @DisplayName("publica mudança de reserva no destino configurado")
  void publicaMudancaDeReservaNoDestinoConfigurado() {
    final var event = BookingRoomPaymentCompleted.builder().reservationOrderId("order").build();
    this.verifyPublisher(event, new BookingRoomStatusChangedPublisherImpl(this.rabbit, this.routes, this.exchanges)::publish, "booking", "status");
  }

  @Test
  @DisplayName("publica atualização do cliente no destino configurado")
  void publicaAtualizacaoDoClienteNoDestinoConfigurado() {
    final var event = CustomerBookingCompletedEvent.builder().reservationOrderId("order").build();
    this.verifyPublisher(event, new CustomerBookingRoomStatusUpdatedPublisherImpl(this.rabbit, this.routes, this.exchanges)::publish, "customer", "update");
  }

  @Test
  @DisplayName("publica pedido de pagamento no destino configurado")
  void publicaPedidoDePagamentoNoDestinoConfigurado() {
    final var event = PaymentRequestedEvent.builder().reservationOrderId("order").build();
    this.verifyPublisher(event, new PaymentRequestedPublisherImpl(this.rabbit, this.routes, this.exchanges)::publish, "payment", "charge");
  }

  /**
   * Registra o contrato: o evento só é dado como publicado depois que o broker confirma, e a
   * falha de transporte sobe como {@link MessagePublishException} em vez de ser absorvida.
   */
  private <T> void verifyPublisher(final T event, final Consumer<T> publisher, final String exchange, final String route) {
    doAnswer(invocation -> invocation.getArgument(3, CorrelationData.class).getFuture()
      .complete(new CorrelationData.Confirm(true, null)))
      .when(this.rabbit).convertAndSend(eq(exchange), eq(route), eq(event), any(CorrelationData.class));
    publisher.accept(event);
    verify(this.rabbit).convertAndSend(eq(exchange), eq(route), eq(event), any(CorrelationData.class));
    final var failure = new AmqpException("broker unavailable");
    doThrow(failure).when(this.rabbit).convertAndSend(eq(exchange), eq(route), eq(event), any(CorrelationData.class));
    assertThatThrownBy(() -> publisher.accept(event))
      .isInstanceOf(MessagePublishException.class)
      .hasCause(failure);
    verify(this.rabbit, times(2)).convertAndSend(eq(exchange), eq(route), eq(event), any(CorrelationData.class));
    verifyNoMoreInteractions(this.rabbit);
  }

  @Test
  @DisplayName("encaminha a resposta de reserva e propaga falha do handler")
  void listenerDeReservaEncaminhaMesmaMensagemEPropagaFalhaDoHandler() {
    final var handler = mock(BookingRoomResponseHandler.class);
    final var listener = new BookingRoomResponseListenerImpl(handler);
    final var event = BookingRoomConfirmedEvent.builder().reservationOrderId("order").build();
    listener.listen(event);
    verify(handler).handle(event);
    final var failure = new IllegalStateException("handler failed");
    doThrow(failure).when(handler).handle(event);
    assertThatThrownBy(() -> listener.listen(event)).isSameAs(failure);
  }

  @Test
  @DisplayName("encaminha a resposta de pagamento e propaga falha do handler")
  void listenerDePagamentoEncaminhaMesmaMensagemEPropagaFalhaDoHandler() {
    final var handler = mock(PaymentResponseHandler.class);
    final var listener = new PaymentResponseListenerImpl(handler);
    final var event = PaymentCompletedEvent.builder().reservationOrderId("order").build();
    listener.listen(event);
    verify(handler).handle(event);
    final var failure = new IllegalStateException("handler failed");
    doThrow(failure).when(handler).handle(event);
    assertThatThrownBy(() -> listener.listen(event)).isSameAs(failure);
  }
}
