package com.hotel.booking.system.hotel.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.domain.event.customer.CustomerBookingStatusUpdatedEvent;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.CustomerBookingRoomStatusUpdatedPublisher;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.RoutingKeyProperties;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@Slf4j
@Publisher
@AllArgsConstructor
public class CustomerBookingRoomStatusUpdatedPublisherImpl implements CustomerBookingRoomStatusUpdatedPublisher {

  private final RabbitTemplate rabbitTemplate;
  private final RoutingKeyProperties routingKeyProperties;
  private final ExchangeProperties exchangeProperties;

  @Override
  public void publish(final CustomerBookingStatusUpdatedEvent event) {
    try {
      log.info(
        "Publishing event: {} to exchange={} | routingKey={}",
        event,
        this.exchangeProperties.customerBooking(),
        this.routingKeyProperties.customerBookingUpdate()
      );
      this.rabbitTemplate.convertAndSend(
        this.exchangeProperties.customerBooking(),
        this.routingKeyProperties.customerBookingUpdate(),
        event
      );
    } catch (final Exception exception) {
      log.error(
        "Failed to publish event: {} to exchange={} | routingKey={}",
        event,
        this.exchangeProperties.customerBooking(),
        this.routingKeyProperties.customerBookingUpdate(),
        exception
      );
    }

  }
}
