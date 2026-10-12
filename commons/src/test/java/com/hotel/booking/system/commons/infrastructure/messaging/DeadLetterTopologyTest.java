package com.hotel.booking.system.commons.infrastructure.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@DisplayName("Topologia de dead-letter")
class DeadLetterTopologyTest {

  @Test
  @DisplayName("nomeia a DLQ pelo sufixo da fila principal")
  void nomeiaADlqPeloSufixoDaFilaPrincipal() {
    assertThat(DeadLetterTopology.deadLetterQueueName("booking-room-requested"))
      .isEqualTo("booking-room-requested.dlq");
  }

  @Test
  @DisplayName("declara a fila principal durável apontando para o DLX e para a própria DLQ")
  void declaraAFilaPrincipalComOsArgumentosDeDeadLetter() {
    final var queue = DeadLetterTopology.queue("payment-request");

    assertThat(queue.getName()).isEqualTo("payment-request");
    assertThat(queue.isDurable()).isTrue();
    assertThat(queue.isAutoDelete()).isFalse();
    assertThat(queue.isExclusive()).isFalse();
    assertThat(queue.getArguments())
      .containsEntry("x-dead-letter-exchange", DeadLetterTopology.EXCHANGE)
      .containsEntry("x-dead-letter-routing-key", "payment-request.dlq");
  }

  @Test
  @DisplayName("declara o DLX, uma DLQ durável por fila e o binding de cada uma")
  void declaraDlxDlqsEBindings() {
    final var declarables = DeadLetterTopology.declarables("a", "b");

    assertThat(declarables.getDeclarablesByType(DirectExchange.class))
      .singleElement()
      .satisfies(exchange -> {
        assertThat(exchange.getName()).isEqualTo(DeadLetterTopology.EXCHANGE);
        assertThat(exchange.isDurable()).isTrue();
      });
    assertThat(declarables.getDeclarablesByType(Queue.class))
      .extracting(Queue::getName)
      .containsExactlyInAnyOrder("a.dlq", "b.dlq");
    assertThat(declarables.getDeclarablesByType(Binding.class))
      .extracting(Binding::getExchange, Binding::getRoutingKey, Binding::getDestination)
      .containsExactlyInAnyOrder(
        tuple(DeadLetterTopology.EXCHANGE, "a.dlq", "a.dlq"),
        tuple(DeadLetterTopology.EXCHANGE, "b.dlq", "b.dlq")
      );
  }

  @Test
  @DisplayName("não encadeia a DLQ: ela é durável e não aponta para outro dead-letter")
  void naoEncadeiaADlq() {
    final var declarables = DeadLetterTopology.declarables("a");

    assertThat(declarables.getDeclarablesByType(Queue.class))
      .singleElement()
      .satisfies(queue -> {
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments()).doesNotContainKeys("x-dead-letter-exchange", "x-dead-letter-routing-key");
      });
  }

}
