package com.hotel.booking.system.booking.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.booking.service.core.ports.spi.messaging.publisher.BookingRoomResponsePublisher;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.RoutingKeyProperties;
import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomResponseEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@Slf4j
@Publisher
@AllArgsConstructor
public class BookingRoomResponsePublisherImpl implements BookingRoomResponsePublisher {

  private final RabbitTemplate rabbitTemplate;
  private final RoutingKeyProperties routingKeyProperties;
  private final ExchangeProperties exchangeProperties;

  @Override
  public void publish(final BookingRoomResponseEvent event) {
    try {
      log.info(
        "Publishing event: {} to exchange: {} | routingKey={}",
        event,
        this.exchangeProperties.bookingRoom(),
        this.routingKeyProperties.bookingRoomConfirmation()
      );
      this.rabbitTemplate.convertAndSend(
        this.exchangeProperties.bookingRoom(),
        this.routingKeyProperties.bookingRoomConfirmation(),
        event
      );
    } catch (final Exception exception) {
      log.error(
        "Failed to publish event: {} to exchange={} | routingKey={}",
        event,
        this.exchangeProperties.bookingRoom(),
        this.routingKeyProperties.bookingRoomConfirmation()
      );
    }
  }

}
