package com.hotel.booking.system.hotel.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.domain.event.PaymentRequestedEvent;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.PaymentRequestedPublisher;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.RoutingKeyProperties;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@Slf4j
@Publisher
@AllArgsConstructor
public class PaymentRequestedPublisherImpl implements PaymentRequestedPublisher {

  private final RabbitTemplate rabbitTemplate;
  private final RoutingKeyProperties routingKeyProperties;
  private final ExchangeProperties exchangeProperties;

  @Override
  public void publish(final PaymentRequestedEvent event) {
    try {
      log.info(
        "Publishing event: {} to exchange={} | routingKey={}",
        event,
        this.exchangeProperties.payment(),
        this.routingKeyProperties.paymentRequest()
      );
      this.rabbitTemplate.convertAndSend(
        this.exchangeProperties.payment(),
        this.routingKeyProperties.paymentRequest(),
        event
      );
    } catch (final Exception exception) {
      log.error(
        "Failed to publish event: {} to exchange={} | routingKey={}",
        event,
        this.exchangeProperties.payment(),
        this.routingKeyProperties.paymentRequest(),
        exception
      );
    }

  }
}
