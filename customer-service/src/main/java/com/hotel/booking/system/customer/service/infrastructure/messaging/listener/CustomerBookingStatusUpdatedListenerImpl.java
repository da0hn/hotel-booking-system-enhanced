package com.hotel.booking.system.customer.service.infrastructure.messaging.listener;

import com.hotel.booking.system.commons.core.application.annotation.Listener;
import com.hotel.booking.system.commons.core.domain.event.customer.CustomerBookingStatusUpdatedEvent;
import com.hotel.booking.system.customer.service.core.ports.api.messaging.CustomerBookingStatusUpdatedHandler;
import com.hotel.booking.system.customer.service.core.ports.spi.messaging.listener.CustomerBookingStatusUpdatedListener;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Listener
@AllArgsConstructor
public class CustomerBookingStatusUpdatedListenerImpl implements CustomerBookingStatusUpdatedListener {

  private final CustomerBookingStatusUpdatedHandler handler;

  @Override
  @RabbitListener(queues = "${app.rabbitmq.queue.customer-booking-update}")
  @Transactional
  public void listen(final List<CustomerBookingStatusUpdatedEvent> events) {
    log.info("CustomerBookingStatusUpdatedEvent received: {}", events.size());
    try {
      for (final var event : events) {
        this.handler.handle(event);
      }
    } catch (final Exception e) {
      log.error("An error occurred while trying processing CustomerBookingStatusUpdatedEvent", e);
      throw new IllegalStateException("Failed to process CustomerBookingStatusUpdatedEvent", e);
    }
  }

}
