package com.hotel.booking.system.commons.infrastructure.messaging.outbox;

import com.hotel.booking.system.commons.infrastructure.messaging.AbstractRabbitPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Envia uma linha do outbox ao broker com a mesma espera de confirm e o mesmo tratamento de
 * return do {@link AbstractRabbitPublisher}. O exchange e a routing key vêm da linha, não do
 * construtor: cada evento pode ter um destino diferente.
 */
public final class OutboxBrokerPublisher extends AbstractRabbitPublisher<Object> {

  public OutboxBrokerPublisher(final RabbitTemplate rabbitTemplate) {
    super(rabbitTemplate, "outbox", "outbox");
  }

  /**
   * O destino mora na linha do outbox. Este método herdado publicaria numa exchange que não
   * existe; o relay usa {@link #publishTo(String, String, Object)}.
   */
  @Override
  public void publish(final Object event) {
    throw new UnsupportedOperationException("O relay publica com publishTo, usando o destino gravado na linha");
  }

}
