package com.hotel.booking.system.hotel.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.application.outbox.Outbox;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomStatusUpdatedEvent;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.OutboxPublisher;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.BookingRoomStatusChangedPublisher;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.RoutingKeyProperties;

@Publisher
public class BookingRoomStatusChangedPublisherImpl
  extends OutboxPublisher<BookingRoomStatusUpdatedEvent>
  implements BookingRoomStatusChangedPublisher {

  public BookingRoomStatusChangedPublisherImpl(
    final Outbox outbox,
    final RoutingKeyProperties routingKeyProperties,
    final ExchangeProperties exchangeProperties
  ) {
    super(outbox, exchangeProperties.bookingRoom(), routingKeyProperties.bookingRoomStatusChanged());
  }

  @Override
  protected String aggregateId(final BookingRoomStatusUpdatedEvent event) {
    return event.getReservationOrderId();
  }
}
