package com.hotel.booking.system.hotel.service.infrastructure.configuration;

import com.hotel.booking.system.commons.core.domain.event.PaymentRequestedEvent;
import com.hotel.booking.system.hotel.service.infrastructure.configuration.*;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DisplayName("Configuração de mensageria do serviço de hotéis")
class MessagingConfigurationTest {
  @Test
  @DisplayName("declara exchanges, filas, bindings e conversor duráveis")
  void declaraTopologiaDuravelComBindingsConsistentes() {
    final var config = new RabbitMQConfiguration(
      new RoutingKeyProperties("r1", "r2", "r3", "r4", "r5", "r6"),
      new QueueProperties("q1", "q2", "q3", "q4", "q5", "q6"),
      new ExchangeProperties("booking", "payment", "customer"));
    final var booking = config.bookingRoomExchange();
    final var payment = config.paymentExchange();
    final var customer = config.customerBookingExchange();
    final var queues = List.of(config.bookingRoomRequestedQueue(), config.bookingRoomConfirmationQueue(),
      config.bookingRoomStatusChangedQueue(), config.paymentRequestQueue(), config.paymentConfirmationQueue(), config.customerBookingUpdateQueue());
    assertThat(queues).allSatisfy(queue -> {
      assertThat(queue.isDurable()).isTrue();
      assertThat(queue.isAutoDelete()).isFalse();
      assertThat(queue.isExclusive()).isFalse();
    });
    assertThat(List.of(booking, payment, customer)).allSatisfy(exchange -> {
      assertThat(exchange.isDurable()).isTrue();
      assertThat(exchange.getType()).isEqualTo("direct");
    });
    final var bindings = List.of(config.bookingRoomRequestedBinding(booking, queues.get(0)),
      config.bookingRoomConfirmationBinding(booking, queues.get(1)), config.bookingRoomStatusChangedBinding(booking, queues.get(2)),
      config.paymentRequestBinding(payment, queues.get(3)), config.paymentConfirmationBinding(payment, queues.get(4)),
      config.customerBookingUpdateBinding(customer, queues.get(5)));
    assertThat(bindings).extracting(Binding::getExchange).containsExactly("booking", "booking", "booking", "payment", "payment", "customer");
    assertThat(bindings).extracting(Binding::getRoutingKey).containsExactly("r1", "r2", "r3", "r4", "r5", "r6");
    assertThat(bindings).extracting(Binding::getDestination).containsExactly("q1", "q2", "q3", "q4", "q5", "q6");
    final var json = new JsonMapperConfiguration().jsonMapper();
    final var converter = config.jsonMessageConverter(json);
    final var event = PaymentRequestedEvent.builder().reservationOrderId("order").customerId("customer")
      .bookingRoomId("booking").totalPrice(new BigDecimal("19.9999")).build();
    final var message = converter.toMessage(event, new MessageProperties());
    assertThat(message.getMessageProperties().getHeader("__TypeId__").toString()).isEqualTo(PaymentRequestedEvent.class.getName());
    assertThat(converter.fromMessage(message)).usingRecursiveComparison()
      .ignoringFields("createdAt").isEqualTo(event);
    assertThat(json.writeValueAsString(new Object())).isEqualTo("{}");
    assertThat(json.readValue("{\"reservationOrderId\":\"order\",\"futureField\":true}", PaymentRequestedEvent.class).getReservationOrderId()).isEqualTo("order");
  }
}
