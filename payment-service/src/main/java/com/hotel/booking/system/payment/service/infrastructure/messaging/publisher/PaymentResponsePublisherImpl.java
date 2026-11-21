package com.hotel.booking.system.payment.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.domain.event.PaymentResponseEvent;
import com.hotel.booking.system.commons.infrastructure.messaging.AbstractRabbitPublisher;
import com.hotel.booking.system.payment.service.core.ports.spi.messaging.publisher.PaymentResponsePublisher;
import com.hotel.booking.system.payment.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.payment.service.infrastructure.messaging.properties.RoutingKeyProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publica direto no broker. O {@code payment-service} não tem banco, então não há transação em
 * que gravar um outbox: a falha de publicação sobe pelo listener, o retry e a DLQ tratam, e a
 * idempotência em memória cobre a reentrega dentro do processo. Os serviços com banco
 * ({@code hotel} e {@code booking}) gravam no outbox em vez deste caminho.
 */
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
