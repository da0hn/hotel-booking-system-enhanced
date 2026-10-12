package com.hotel.booking.system.hotel.service.infrastructure.messaging.publisher;

import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.domain.event.customer.CustomerBookingStatusUpdatedEvent;
import com.hotel.booking.system.commons.infrastructure.messaging.AbstractRabbitPublisher;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.CustomerBookingRoomStatusUpdatedPublisher;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.hotel.service.infrastructure.messaging.properties.RoutingKeyProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@Publisher
public class CustomerBookingRoomStatusUpdatedPublisherImpl
  extends AbstractRabbitPublisher<CustomerBookingStatusUpdatedEvent>
  implements CustomerBookingRoomStatusUpdatedPublisher {

  public CustomerBookingRoomStatusUpdatedPublisherImpl(
    final RabbitTemplate rabbitTemplate,
    final RoutingKeyProperties routingKeyProperties,
    final ExchangeProperties exchangeProperties
  ) {
    super(rabbitTemplate, exchangeProperties.customerBooking(), routingKeyProperties.customerBookingUpdate());
  }
}
