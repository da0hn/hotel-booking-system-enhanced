package com.hotel.booking.system.payment.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.domain.event.PaymentResponseEvent;
import com.hotel.booking.system.payment.service.core.ports.spi.messaging.publisher.PaymentResponsePublisher;
import com.hotel.booking.system.payment.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.payment.service.infrastructure.messaging.properties.RoutingKeyProperties;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@Slf4j
@Publisher
@AllArgsConstructor
public class PaymentResponsePublisherImpl implements PaymentResponsePublisher {

  private final RabbitTemplate rabbitTemplate;
  private final RoutingKeyProperties routingKeyProperties;
  private final ExchangeProperties exchangeProperties;

  @Override
  public void publish(final PaymentResponseEvent event) {
    try {
      log.info(
        "Publishing event: {} to exchange: {} | routingKey={}",
        event,
        this.exchangeProperties.payment(),
        this.routingKeyProperties.paymentConfirmation()
      );
      this.rabbitTemplate.convertAndSend(
        this.exchangeProperties.payment(),
        this.routingKeyProperties.paymentConfirmation(),
        event
      );
      log.info(
        "Event: {} published successfully to exchange: {} | routingKey={}",
        event,
        this.exchangeProperties.payment(),
        this.routingKeyProperties.paymentConfirmation()
      );
    } catch (final Exception exception) {
      log.error(
        "Failed to publish event: {} to exchange={} | routingKey={}",
        event,
        this.exchangeProperties.payment(),
        this.routingKeyProperties.paymentConfirmation(),
        exception
      );
    }
  }

}
