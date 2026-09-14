package com.hotel.booking.system.booking.service.infrastructure.messaging.listener;

import com.hotel.booking.system.booking.service.core.ports.api.messaging.BookingRoomRequestedHandler;
import com.hotel.booking.system.booking.service.core.ports.spi.messaging.listener.BookingRoomRequestedListener;
import com.hotel.booking.system.commons.core.application.annotation.Listener;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomRequestedEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Listener
@AllArgsConstructor
public class BookingRoomRequestedRabbitMQListener implements BookingRoomRequestedListener {

  private final BookingRoomRequestedHandler handler;


  @RabbitListener(queues = "${app.rabbitmq.queue.booking-room-requested}")
  @Transactional
  public void listen(final BookingRoomRequestedEvent event) {
    log.info(
      "BookingRoomRequestedEvent received {}",
      event
    );
    this.handler.handle(event);
  }

}
