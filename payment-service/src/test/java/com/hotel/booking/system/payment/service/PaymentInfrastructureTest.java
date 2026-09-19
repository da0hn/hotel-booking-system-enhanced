package com.hotel.booking.system.payment.service;

import com.hotel.booking.system.commons.core.domain.event.PaymentCompletedEvent;
import com.hotel.booking.system.commons.core.domain.event.PaymentRequestedEvent;
import com.hotel.booking.system.commons.core.domain.valueobject.PaymentStatus;
import com.hotel.booking.system.payment.service.infrastructure.configuration.JsonMapperConfiguration;
import com.hotel.booking.system.payment.service.infrastructure.configuration.PaymentBeanConfiguration;
import com.hotel.booking.system.payment.service.infrastructure.configuration.RabbitMQConfiguration;
import com.hotel.booking.system.payment.service.core.application.mapper.PaymentUseCaseMapperImpl;
import com.hotel.booking.system.payment.service.core.application.messaging.PaymentRequestedHandlerImpl;
import com.hotel.booking.system.payment.service.core.application.usecase.PayOrderUseCaseMockImpl;
import com.hotel.booking.system.payment.service.core.ports.api.messaging.PaymentRequestedHandler;
import com.hotel.booking.system.payment.service.core.ports.api.usecase.PayOrderUseCase;
import com.hotel.booking.system.payment.service.core.ports.spi.messaging.publisher.PaymentResponsePublisher;
import com.hotel.booking.system.payment.service.infrastructure.messaging.listener.PaymentRequestedListenerImpl;
import com.hotel.booking.system.payment.service.infrastructure.messaging.publisher.PaymentResponsePublisherImpl;
import com.hotel.booking.system.payment.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.payment.service.infrastructure.messaging.properties.QueueProperties;
import com.hotel.booking.system.payment.service.infrastructure.messaging.properties.RoutingKeyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.SpringApplication;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Infraestrutura do serviço de pagamento")
class PaymentInfrastructureTest {

  @Test
  @DisplayName("declara a topologia AMQP durável e converte eventos confiáveis")
  void declaresDurableAmqpTopologyAndConvertsTrustedEvents() {
    final var config = new RabbitMQConfiguration(
      new RoutingKeyProperties("payment.request", "payment.confirmation"),
      new QueueProperties("payment.request.queue", "payment.confirmation.queue"),
      new ExchangeProperties("payment.exchange"));
    final var exchange = config.paymentExchange();
    final var requestQueue = config.paymentRequestQueue();
    final var confirmationQueue = config.paymentConfirmationQueue();
    final var binding = config.paymentConfirmationBinding(exchange, confirmationQueue);
    assertThat(exchange.getName()).isEqualTo("payment.exchange");
    assertThat(exchange.isDurable()).isTrue();
    assertThat(requestQueue.getName()).isEqualTo("payment.request.queue");
    assertThat(requestQueue.isDurable()).isTrue();
    assertThat(confirmationQueue.getName()).isEqualTo("payment.confirmation.queue");
    assertThat(binding.getExchange()).isEqualTo("payment.exchange");
    assertThat(binding.getDestination()).isEqualTo("payment.confirmation.queue");
    assertThat(binding.getRoutingKey()).isEqualTo("payment.confirmation");
    final var mapper = new JsonMapperConfiguration().jsonMapper();
    assertThat(mapper.isEnabled(SerializationFeature.FAIL_ON_EMPTY_BEANS)).isFalse();
    assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
    final var converter = config.jsonMessageConverter(mapper);
    final var event = PaymentCompletedEvent.builder().reservationOrderId("order").customerId("customer")
      .status(PaymentStatus.COMPLETED).build();
    final var message = converter.toMessage(event, new MessageProperties());
    assertThat(message.getMessageProperties().getHeader("__TypeId__").toString())
      .isEqualTo(PaymentCompletedEvent.class.getName());
    assertThat(converter.fromMessage(message)).isInstanceOf(PaymentCompletedEvent.class);
  }

  @Test
  @DisplayName("cria os componentes do fluxo de pagamento por injeção construtora")
  void createsPaymentComponentsThroughConstructorInjection() {
    final var configuration = new PaymentBeanConfiguration();
    final var mapper = new PaymentUseCaseMapperImpl();
    final var useCase = new PayOrderUseCaseMockImpl(mapper, 0);
    final var publisher = mock(PaymentResponsePublisher.class);
    final var handler = configuration.paymentRequestedHandler(useCase, mapper, publisher);
    assertThat(mapper).isInstanceOf(PaymentUseCaseMapperImpl.class);
    assertThat(useCase).isInstanceOf(PayOrderUseCaseMockImpl.class);
    assertThat(handler).isInstanceOf(PaymentRequestedHandlerImpl.class);
  }

  @Test
  @DisplayName("encaminha o evento recebido e publica a resposta na rota configurada")
  void forwardsIncomingEventAndPublishesResponseOnConfiguredRoute() {
    final var handler = mock(PaymentRequestedHandler.class);
    final var request = PaymentRequestedEvent.builder().reservationOrderId("order").build();
    new PaymentRequestedListenerImpl(handler).listen(request);
    verify(handler).handle(request);

    final var template = mock(RabbitTemplate.class);
    final var response = PaymentCompletedEvent.builder().reservationOrderId("order").build();
    final var publisher = new PaymentResponsePublisherImpl(template,
      new RoutingKeyProperties("request", "confirmation"), new ExchangeProperties("exchange"));
    publisher.publish(response);
    verify(template).convertAndSend("exchange", "confirmation", response);
    doThrow(new IllegalStateException("broker indisponível")).when(template)
      .convertAndSend("exchange", "confirmation", response);
    assertThatCode(() -> publisher.publish(response)).doesNotThrowAnyException();
    verify(template, times(2)).convertAndSend("exchange", "confirmation", response);
  }

  @Test
  @DisplayName("encaminha os argumentos para a inicialização do Spring Boot")
  void forwardsArgumentsToSpringBoot() {
    assertThat(new PaymentServiceApplication()).isNotNull();
    try (final var spring = mockStatic(SpringApplication.class)) {
      final var args = new String[]{"--spring.profiles.active=test"};
      PaymentServiceApplication.main(args);
      spring.verify(() -> SpringApplication.run(PaymentServiceApplication.class, args));
    }
  }
}
