package com.hotel.booking.system.booking.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.booking.service.core.ports.spi.messaging.publisher.BookingRoomResponsePublisher;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.RoutingKeyProperties;
import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.application.outbox.Outbox;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomResponseEvent;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.OutboxPublisher;

@Publisher
public class BookingRoomResponsePublisherImpl
  extends OutboxPublisher<BookingRoomResponseEvent>
  implements BookingRoomResponsePublisher {

  public BookingRoomResponsePublisherImpl(
    final Outbox outbox,
    final RoutingKeyProperties routingKeyProperties,
    final ExchangeProperties exchangeProperties
  ) {
    super(outbox, exchangeProperties.bookingRoom(), routingKeyProperties.bookingRoomConfirmation());
  }

  @Override
  protected String aggregateId(final BookingRoomResponseEvent event) {
    return event.getReservationOrderId();
  }
}
