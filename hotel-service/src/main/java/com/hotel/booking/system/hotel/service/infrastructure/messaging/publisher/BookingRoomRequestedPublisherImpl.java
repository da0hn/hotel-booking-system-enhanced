package com.hotel.booking.system.hotel.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomRequestedEvent;
import com.hotel.booking.system.commons.infrastructure.messaging.AbstractRabbitPublisher;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.BookingRoomRequestedPublisher;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.RoutingKeyProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@Publisher
public class BookingRoomRequestedPublisherImpl
  extends AbstractRabbitPublisher<BookingRoomRequestedEvent>
  implements BookingRoomRequestedPublisher {

  public BookingRoomRequestedPublisherImpl(
    final RabbitTemplate rabbitTemplate,
    final RoutingKeyProperties routingKeyProperties,
    final ExchangeProperties exchangeProperties
  ) {
    super(rabbitTemplate, exchangeProperties.bookingRoom(), routingKeyProperties.bookingRoomRequested());
  }
}
