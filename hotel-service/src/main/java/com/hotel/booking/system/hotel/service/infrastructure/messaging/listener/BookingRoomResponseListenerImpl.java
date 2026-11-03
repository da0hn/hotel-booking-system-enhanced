package com.hotel.booking.system.hotel.service.infrastructure.messaging.listener;

import com.hotel.booking.system.commons.core.application.annotation.Listener;
import com.hotel.booking.system.commons.core.application.idempotency.IdempotentEventConsumer;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomResponseEvent;
import com.hotel.booking.system.hotel.service.core.ports.api.messaging.BookingRoomResponseHandler;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.listener.BookingRoomResponseListener;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Listener
@AllArgsConstructor
public class BookingRoomResponseListenerImpl implements BookingRoomResponseListener {

  private final BookingRoomResponseHandler handler;

  private final IdempotentEventConsumer idempotentEventConsumer;

  /**
   * Transacional só por causa do registro do {@code eventId}: o handler apenas publica, mas a
   * reentrega de uma resposta do booking geraria um segundo {@code PaymentRequestedEvent} — com
   * {@code eventId} novo, portanto indistinguível para o payment. Cortar a duplicata aqui
   * protege o resto da cadeia.
   */
  @Override
  @RabbitListener(queues = "${app.rabbitmq.queue.booking-room-confirmation}")
  @Transactional
  public void listen(final BookingRoomResponseEvent event) {
    log.info("BookingRoomResponseEvent received: {}, ", event);
    this.idempotentEventConsumer.consume(event, () -> this.handler.handle(event));
  }

}
