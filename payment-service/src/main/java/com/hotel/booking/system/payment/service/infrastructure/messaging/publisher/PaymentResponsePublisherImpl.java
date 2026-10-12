package com.hotel.booking.system.payment.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.domain.event.PaymentResponseEvent;
import com.hotel.booking.system.commons.infrastructure.messaging.AbstractRabbitPublisher;
import com.hotel.booking.system.payment.service.core.ports.spi.messaging.publisher.PaymentResponsePublisher;
import com.hotel.booking.system.payment.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.payment.service.infrastructure.messaging.properties.RoutingKeyProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@Publisher
public class PaymentResponsePublisherImpl
  extends AbstractRabbitPublisher<PaymentResponseEvent>
  implements PaymentResponsePublisher {

  public PaymentResponsePublisherImpl(
    final RabbitTemplate rabbitTemplate,
    final RoutingKeyProperties routingKeyProperties,
    final ExchangeProperties exchangeProperties
  ) {
    super(rabbitTemplate, exchangeProperties.payment(), routingKeyProperties.paymentConfirmation());
  }
}
