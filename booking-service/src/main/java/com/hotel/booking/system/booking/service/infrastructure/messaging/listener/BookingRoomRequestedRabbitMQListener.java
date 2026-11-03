package com.hotel.booking.system.booking.service.infrastructure.messaging.listener;

import com.hotel.booking.system.booking.service.core.ports.api.messaging.BookingRoomRequestedHandler;
import com.hotel.booking.system.booking.service.core.ports.spi.messaging.listener.BookingRoomRequestedListener;
import com.hotel.booking.system.commons.core.application.annotation.Listener;
import com.hotel.booking.system.commons.core.application.idempotency.IdempotentEventConsumer;
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

  private final IdempotentEventConsumer idempotentEventConsumer;

  /**
   * O registro do {@code eventId} e a reserva entram na mesma transação: o commit leva os dois e
   * o rollback desfaz os dois. É por isso que a reentrega de um evento já processado pode ser
   * descartada com segurança, e a de um evento que falhou, não.
   */
  @RabbitListener(queues = "${app.rabbitmq.queue.booking-room-requested}")
  @Transactional
  public void listen(final BookingRoomRequestedEvent event) {
    log.info(
      "BookingRoomRequestedEvent received {}",
      event
    );
    this.idempotentEventConsumer.consume(event, () -> this.handler.handle(event));
  }

}
