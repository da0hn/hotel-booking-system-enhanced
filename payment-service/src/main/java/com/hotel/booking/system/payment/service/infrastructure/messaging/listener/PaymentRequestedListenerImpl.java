package com.hotel.booking.system.payment.service.infrastructure.messaging.listener;

import com.hotel.booking.system.commons.core.application.annotation.Listener;
import com.hotel.booking.system.commons.core.domain.event.PaymentRequestedEvent;
import com.hotel.booking.system.payment.service.core.ports.api.messaging.PaymentRequestedHandler;
import com.hotel.booking.system.payment.service.core.ports.spi.messaging.listener.PaymentRequestedListener;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

@Slf4j
@Listener
@AllArgsConstructor
public class PaymentRequestedListenerImpl implements PaymentRequestedListener {

  private final PaymentRequestedHandler handler;

  @Override
  @RabbitListener(queues = "${app.rabbitmq.queue.payment-request}")
  public void listen(final PaymentRequestedEvent event) {
    log.info("PaymentRequestedEvent received, {}", event);
    this.handler.handle(event);
  }
}
