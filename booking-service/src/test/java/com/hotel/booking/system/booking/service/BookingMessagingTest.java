package com.hotel.booking.system.booking.service;

import com.hotel.booking.system.booking.service.infrastructure.configuration.*;
import com.hotel.booking.system.booking.service.core.ports.api.messaging.*;
import com.hotel.booking.system.booking.service.infrastructure.messaging.listener.*;
import com.hotel.booking.system.booking.service.infrastructure.messaging.publisher.BookingRoomResponsePublisherImpl;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.*;
import com.hotel.booking.system.commons.core.application.exception.MessagePublishException;
import com.hotel.booking.system.commons.core.domain.event.*;
import com.hotel.booking.system.commons.core.domain.valueobject.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Adaptadores e configuração de mensageria das reservas")
class BookingMessagingTest {
  @Test
  @DisplayName("declara transação nos listeners que alteram o banco")
  void listenersDeclaramTransacaoNasOperacoesDeEscrita() throws NoSuchMethodException {
    final var requested = BookingRoomRequestedRabbitMQListener.class
      .getMethod("listen", BookingRoomRequestedEvent.class)
      .getAnnotation(Transactional.class);
    final var statusChanged = BookingRoomStatusChangedRabbitMQListener.class
      .getMethod("listen", BookingRoomStatusUpdatedEvent.class)
      .getAnnotation(Transactional.class);

    assertThat(requested).isNotNull().extracting(Transactional::readOnly).isEqualTo(false);
    assertThat(statusChanged).isNotNull().extracting(Transactional::readOnly).isEqualTo(false);
  }

  @Test
  @DisplayName("encaminha eventos recebidos sem transformá-los")
  void listenersEncaminhamEventoSemTransformacao() {
    final var requestHandler = mock(BookingRoomRequestedHandler.class);
    final var changeHandler = mock(BookingRoomStatusChangedHandler.class);
    final var request = BookingRoomRequestedEvent.builder().build();
    final var change = BookingRoomPaymentCompleted.builder().build();
    new BookingRoomRequestedRabbitMQListener(requestHandler).listen(request);
    new BookingRoomStatusChangedRabbitMQListener(changeHandler).listen(change);
    verify(requestHandler).handle(request);
    verify(changeHandler).handle(change);
  }

  /**
   * O publisher só retorna depois que o broker confirma a mensagem, e a falha de transporte
   * sobe como {@link MessagePublishException}: dentro do listener ela provoca o rollback.
   */
  @Test
  @DisplayName("publica na rota configurada e propaga falha do broker")
  void publisherUsaRotaConfiguradaEPropagaFalhaDoBroker() {
    final var template = mock(RabbitTemplate.class);
    final var publisher = new BookingRoomResponsePublisherImpl(template,
      new RoutingKeyProperties("request", "response", "change"), new ExchangeProperties("booking"));
    final var event = new BookingRoomConfirmedEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(), CustomerReservationStatus.RESERVED);
    doAnswer(invocation -> invocation.getArgument(3, CorrelationData.class).getFuture()
      .complete(new CorrelationData.Confirm(true, null)))
      .when(template).convertAndSend(eq("booking"), eq("response"), eq(event), any(CorrelationData.class));
    publisher.publish(event);
    verify(template).convertAndSend(eq("booking"), eq("response"), eq(event), any(CorrelationData.class));
    final var failure = new IllegalStateException("broker offline");
    doThrow(failure).when(template).convertAndSend(eq("booking"), eq("response"), eq(event), any(CorrelationData.class));
    assertThatThrownBy(() -> publisher.publish(event))
      .isInstanceOf(MessagePublishException.class)
      .hasCause(failure);
    verify(template, times(2)).convertAndSend(eq("booking"), eq("response"), eq(event), any(CorrelationData.class));
  }

  @Test
  @DisplayName("declara topologia durável e conversor compatível com eventos")
  void configuraTopologiaDuravelEConversorCompatívelComEventos() {
    final var config = new RabbitMQConfiguration(new RoutingKeyProperties("request", "response", "change"),
      new QueueProperties("requests", "responses", "changes"), new ExchangeProperties("booking"));
    final var exchange = config.bookingRoomExchange();
    assertThat(exchange.getName()).isEqualTo("booking");
    assertThat(exchange.isDurable()).isTrue();
    assertThat(exchange.isAutoDelete()).isFalse();
    final var request = config.bookingRoomRequestedQueue();
    final var response = config.bookingRoomConfirmationQueue();
    final var changed = config.bookingRoomStatusChangedQueue();
    assertThat(request.getName()).isEqualTo("requests");
    assertThat(response.getName()).isEqualTo("responses");
    assertThat(changed.getName()).isEqualTo("changes");
    assertThat(java.util.List.of(request, response, changed)).allSatisfy(queue -> {
      assertThat(queue.isDurable()).isTrue();
      assertThat(queue.isExclusive()).isFalse();
      assertThat(queue.isAutoDelete()).isFalse();
    });
    final var binding = config.bookingRoomConfirmationBinding(exchange, response);
    assertThat(binding.getExchange()).isEqualTo("booking");
    assertThat(binding.getDestination()).isEqualTo("responses");
    assertThat(binding.getRoutingKey()).isEqualTo("response");
    final var mapper = new JsonMapperConfiguration().jsonMapper();
    assertThat(mapper.writeValueAsString(new Object())).isEqualTo("{}");
    final var converter = config.jsonMessageConverter(mapper);
    final var event = new BookingRoomConfirmedEvent("order", "customer", CustomerReservationStatus.RESERVED);
    final var message = converter.toMessage(event, new MessageProperties());
    assertThat(message.getMessageProperties().getHeader("__TypeId__").toString()).isEqualTo(BookingRoomConfirmedEvent.class.getName());
    assertThat(converter.fromMessage(message)).isInstanceOfSatisfying(BookingRoomConfirmedEvent.class, value -> {
      assertThat(value.getReservationOrderId()).isEqualTo("order");
      assertThat(value.getCustomerId()).isEqualTo("customer");
      assertThat(value.getStatus()).isEqualTo(CustomerReservationStatus.RESERVED);
    });
  }
}
