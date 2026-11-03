package com.hotel.booking.system.hotel.service.infrastructure.messaging.listener;

import com.hotel.booking.system.commons.core.application.annotation.Listener;
import com.hotel.booking.system.commons.core.application.idempotency.IdempotentEventConsumer;
import com.hotel.booking.system.commons.core.domain.event.PaymentResponseEvent;
import com.hotel.booking.system.hotel.service.core.ports.api.messaging.PaymentResponseHandler;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.listener.PaymentResponseListener;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Listener
@AllArgsConstructor
public class PaymentResponseListenerImpl implements PaymentResponseListener {

  private final PaymentResponseHandler handler;

  private final IdempotentEventConsumer idempotentEventConsumer;

  /**
   * Transacional só por causa do registro do {@code eventId}. A reentrega de uma resposta de
   * pagamento mandaria ao booking um segundo {@code BookingRoomStatusUpdatedEvent}, e uma
   * aprovação seguida de uma recusa levaria a reserva de {@code CONFIRMED} a {@code CANCELED}.
   */
  @Override
  @RabbitListener(queues = "${app.rabbitmq.queue.payment-confirmation}")
  @Transactional
  public void listen(final PaymentResponseEvent event) {
    log.info("PaymentResponseEvent received: {}, ", event);
    this.idempotentEventConsumer.consume(event, () -> this.handler.handle(event));
  }

}
