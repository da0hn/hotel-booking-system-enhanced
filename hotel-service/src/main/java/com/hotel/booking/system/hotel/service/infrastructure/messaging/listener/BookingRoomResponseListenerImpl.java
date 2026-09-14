package com.hotel.booking.system.hotel.service.infrastructure.messaging.listener;

import com.hotel.booking.system.commons.core.application.annotation.Listener;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomResponseEvent;
import com.hotel.booking.system.hotel.service.core.ports.api.messaging.BookingRoomResponseHandler;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.listener.BookingRoomResponseListener;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

@Slf4j
@Listener
@AllArgsConstructor
public class BookingRoomResponseListenerImpl implements BookingRoomResponseListener {

  private final BookingRoomResponseHandler handler;

  @Override
  @RabbitListener(queues = "${app.rabbitmq.queue.booking-room-confirmation}")
  public void listen(final BookingRoomResponseEvent event) {
    log.info("BookingRoomResponseEvent received: {}, ", event);
    this.handler.handle(event);
  }

}
