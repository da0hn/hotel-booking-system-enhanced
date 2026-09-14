package com.hotel.booking.system.customer.service;

import com.hotel.booking.system.commons.core.domain.event.customer.CustomerBookingCompletedEvent;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import com.hotel.booking.system.customer.service.infrastructure.configuration.*;
import com.hotel.booking.system.customer.service.infrastructure.messaging.properties.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.SpringApplication;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Configuração do serviço de clientes")
class CustomerConfigurationTest {
  @Test
  @DisplayName("declara topologia durável e permite converter eventos")
  void declaraTopologiaDuravelERoundTripDoContratoDeEventos() {
    final var mapper = new JsonMapperConfiguration().jsonMapper();
    assertThat(mapper.isEnabled(SerializationFeature.FAIL_ON_EMPTY_BEANS)).isFalse();
    assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
    final var config = new RabbitMQConfiguration(new RoutingKeyProperties("customer.update"),
      new QueueProperties("customer.queue"), new ExchangeProperties("customer.exchange"));
    final var exchange = config.customerBookingExchange();
    final var queue = config.customerBookingUpdateQueue();
    final var binding = config.customerBookingUpdateBinding(exchange, queue);
    assertThat(exchange.getName()).isEqualTo("customer.exchange");
    assertThat(exchange.isDurable()).isTrue();
    assertThat(queue.isDurable()).isTrue();
    assertThat(binding.getDestination()).isEqualTo("customer.queue");
    assertThat(binding.getExchange()).isEqualTo(exchange.getName());
    assertThat(binding.getRoutingKey()).isEqualTo("customer.update");
    final var converter = config.jsonMessageConverter(mapper);
    final var event = CustomerBookingCompletedEvent.builder().customerId("customer")
      .reservationOrderId("reservation").status(CustomerReservationStatus.RESERVED).build();
    final var message = converter.toMessage(event, new MessageProperties());
    final var restored = (CustomerBookingCompletedEvent) converter.fromMessage(message);
    assertThat(restored.getCustomerId()).isEqualTo(event.getCustomerId());
    assertThat(restored.getReservationOrderId()).isEqualTo(event.getReservationOrderId());
    assertThat(restored.getStatus()).isEqualTo(CustomerReservationStatus.RESERVED);
  }

  @Test
  @DisplayName("encaminha os argumentos ao iniciar o Spring Boot")
  void entrypointEncaminhaArgumentosAoSpringBoot() {
    assertThat(new CustomerServiceApplication()).isNotNull();
    try (final var spring = mockStatic(SpringApplication.class)) {
      final var args = new String[]{"--spring.profiles.active=test"};
      CustomerServiceApplication.main(args);
      spring.verify(() -> SpringApplication.run(CustomerServiceApplication.class, args));
    }
  }
}
