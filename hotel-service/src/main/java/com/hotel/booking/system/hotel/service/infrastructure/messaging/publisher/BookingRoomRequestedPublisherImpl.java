package com.hotel.booking.system.hotel.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.application.outbox.Outbox;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomRequestedEvent;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.OutboxPublisher;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.BookingRoomRequestedPublisher;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.RoutingKeyProperties;

@Publisher
public class BookingRoomRequestedPublisherImpl
  extends OutboxPublisher<BookingRoomRequestedEvent>
  implements BookingRoomRequestedPublisher {

  public BookingRoomRequestedPublisherImpl(
    final Outbox outbox,
    final RoutingKeyProperties routingKeyProperties,
    final ExchangeProperties exchangeProperties
  ) {
    super(outbox, exchangeProperties.bookingRoom(), routingKeyProperties.bookingRoomRequested());
  }

  @Override
  protected String aggregateId(final BookingRoomRequestedEvent event) {
    return event.getReservationOrderId();
  }
}
