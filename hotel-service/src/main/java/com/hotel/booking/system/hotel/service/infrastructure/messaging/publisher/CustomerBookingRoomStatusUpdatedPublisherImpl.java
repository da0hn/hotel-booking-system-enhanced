package com.hotel.booking.system.hotel.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.application.outbox.Outbox;
import com.hotel.booking.system.commons.core.domain.event.customer.CustomerBookingStatusUpdatedEvent;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.OutboxPublisher;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.CustomerBookingRoomStatusUpdatedPublisher;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.RoutingKeyProperties;

@Publisher
public class CustomerBookingRoomStatusUpdatedPublisherImpl
  extends OutboxPublisher<CustomerBookingStatusUpdatedEvent>
  implements CustomerBookingRoomStatusUpdatedPublisher {

  public CustomerBookingRoomStatusUpdatedPublisherImpl(
    final Outbox outbox,
    final RoutingKeyProperties routingKeyProperties,
    final ExchangeProperties exchangeProperties
  ) {
    super(outbox, exchangeProperties.customerBooking(), routingKeyProperties.customerBookingUpdate());
  }

  @Override
  protected String aggregateId(final CustomerBookingStatusUpdatedEvent event) {
    return event.getReservationOrderId();
  }
}
