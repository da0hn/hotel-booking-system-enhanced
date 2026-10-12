package com.hotel.booking.system.commons.infrastructure.messaging;

import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.BindingBuilder;

import java.util.ArrayList;

/**
 * A topologia de dead-letter das filas de negócio: um exchange único e uma DLQ por fila.
 * <p>
 * Ela vive no {@code commons} porque cada serviço declara também as filas que não consome (o
 * {@code hotel-service} declara as seis), e o RabbitMQ recusa com {@code PRECONDITION_FAILED}
 * a segunda declaração de uma fila cujos argumentos diferem da primeira. Com os argumentos
 * montados num só lugar, dois serviços não têm como divergir.
 * <p>
 * O nome da DLQ é sempre derivado do nome da fila principal, e não configurado à parte. Quando
 * as filas forem renomeadas por evento e domínio, as DLQs acompanham sem nenhuma outra edição.
 * <p>
 * A DLQ não tem dead-letter próprio: uma mensagem que falha ali não tem para onde ir, e
 * apontá-la de volta a si mesma criaria um laço.
 */
public final class DeadLetterTopology {

  public static final String EXCHANGE = "exchange.dead-letter";

  public static final String QUEUE_SUFFIX = ".dlq";

  private DeadLetterTopology() {
  }

  public static String deadLetterQueueName(final String queueName) {
    return queueName + QUEUE_SUFFIX;
  }

  /**
   * A fila de negócio, durável e com o dead-letter apontando para a DLQ dela. É o que faz uma
   * rejeição sem reenfileiramento terminar na DLQ em vez de ser descartada.
   */
  public static Queue queue(final String queueName) {
    return QueueBuilder.durable(queueName)
      .deadLetterExchange(EXCHANGE)
      .deadLetterRoutingKey(deadLetterQueueName(queueName))
      .build();
  }

  /**
   * O exchange de dead-letter, a DLQ de cada fila informada e o binding que liga uma à outra
   * pela routing key igual ao nome da DLQ. Declarar o mesmo conjunto em mais de um serviço é
   * idempotente.
   */
  public static Declarables declarables(final String... queueNames) {
    final var exchange = new DirectExchange(EXCHANGE);
    final var declarables = new ArrayList<Declarable>();
    declarables.add(exchange);
    for (final var queueName : queueNames) {
      final var deadLetterQueueName = deadLetterQueueName(queueName);
      final var deadLetterQueue = QueueBuilder.durable(deadLetterQueueName).build();
      declarables.add(deadLetterQueue);
      declarables.add(BindingBuilder.bind(deadLetterQueue).to(exchange).with(deadLetterQueueName));
    }
    return new Declarables(declarables);
  }

}
