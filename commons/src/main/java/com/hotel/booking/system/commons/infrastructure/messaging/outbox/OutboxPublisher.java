package com.hotel.booking.system.commons.infrastructure.messaging.outbox;

import com.hotel.booking.system.commons.core.application.outbox.Outbox;

/**
 * Publicador que grava o evento no outbox em vez de falar com o broker.
 *
 * <p>O destino (exchange e routing key) continua resolvido aqui, na borda de infraestrutura,
 * e fica gravado na linha: o relay publica exatamente para onde este publicador publicaria.
 * A confirmação de entrega não acontece nesta classe — quem a faz é o relay, pelo
 * {@code AbstractRabbitPublisher}. Um {@code publish} que engolisse a falha de gravar a linha
 * reabriria a janela que o outbox fecha, então a exceção do banco sobe.</p>
 *
 * @param <T> o evento que esta implementação grava
 */
public abstract class OutboxPublisher<T> {

  private final Outbox outbox;

  private final String exchange;

  private final String routingKey;

  protected OutboxPublisher(final Outbox outbox, final String exchange, final String routingKey) {
    this.outbox = outbox;
    this.exchange = exchange;
    this.routingKey = routingKey;
  }

  public void publish(final T event) {
    this.outbox.append(this.aggregateId(event), this.exchange, this.routingKey, event);
  }

  /**
   * Identificador que ordena os eventos do mesmo fluxo. Na saga, o {@code reservationOrderId}.
   */
  protected abstract String aggregateId(T event);

}
