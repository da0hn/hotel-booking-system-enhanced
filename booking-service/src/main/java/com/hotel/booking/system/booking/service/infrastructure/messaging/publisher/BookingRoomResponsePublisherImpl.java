package com.hotel.booking.system.booking.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.booking.service.core.ports.spi.messaging.publisher.BookingRoomResponsePublisher;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.RoutingKeyProperties;
import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomResponseEvent;
import com.hotel.booking.system.commons.infrastructure.messaging.AbstractRabbitPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@Publisher
public class BookingRoomResponsePublisherImpl
  extends AbstractRabbitPublisher<BookingRoomResponseEvent>
  implements BookingRoomResponsePublisher {

  public BookingRoomResponsePublisherImpl(
    final RabbitTemplate rabbitTemplate,
    final RoutingKeyProperties routingKeyProperties,
    final ExchangeProperties exchangeProperties
  ) {
    super(rabbitTemplate, exchangeProperties.bookingRoom(), routingKeyProperties.bookingRoomConfirmation());
  }
}
