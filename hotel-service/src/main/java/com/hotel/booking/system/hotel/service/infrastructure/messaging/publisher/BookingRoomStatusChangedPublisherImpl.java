package com.hotel.booking.system.hotel.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomStatusUpdatedEvent;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.BookingRoomStatusChangedPublisher;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.RoutingKeyProperties;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@Slf4j
@Publisher
@AllArgsConstructor
public class BookingRoomStatusChangedPublisherImpl implements BookingRoomStatusChangedPublisher {

  private final RabbitTemplate rabbitTemplate;
  private final RoutingKeyProperties routingKeyProperties;
  private final ExchangeProperties exchangeProperties;

  @Override
  public void publish(final BookingRoomStatusUpdatedEvent event) {
    try {
      log.info(
        "Publishing event: {} to exchange={} | routingKey={}",
        event,
        this.exchangeProperties.bookingRoom(),
        this.routingKeyProperties.bookingRoomStatusChanged()
      );
      this.rabbitTemplate.convertAndSend(
        this.exchangeProperties.bookingRoom(),
        this.routingKeyProperties.bookingRoomStatusChanged(),
        event
      );
    } catch (final Exception exception) {
      log.error(
        "Failed to publish event: {} to exchange={} | routingKey={}",
        event,
        this.exchangeProperties.bookingRoom(),
        this.routingKeyProperties.bookingRoomStatusChanged(),
        exception
      );
    }
  }

}
