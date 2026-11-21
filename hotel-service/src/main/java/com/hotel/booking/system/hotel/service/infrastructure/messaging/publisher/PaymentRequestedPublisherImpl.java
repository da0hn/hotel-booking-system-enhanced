package com.hotel.booking.system.hotel.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.application.outbox.Outbox;
import com.hotel.booking.system.commons.core.domain.event.PaymentRequestedEvent;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.OutboxPublisher;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.PaymentRequestedPublisher;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.RoutingKeyProperties;

@Publisher
public class PaymentRequestedPublisherImpl
  extends OutboxPublisher<PaymentRequestedEvent>
  implements PaymentRequestedPublisher {

  public PaymentRequestedPublisherImpl(
    final Outbox outbox,
    final RoutingKeyProperties routingKeyProperties,
    final ExchangeProperties exchangeProperties
  ) {
    super(outbox, exchangeProperties.payment(), routingKeyProperties.paymentRequest());
  }

  @Override
  protected String aggregateId(final PaymentRequestedEvent event) {
    return event.getReservationOrderId();
  }
}
