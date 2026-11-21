package com.hotel.booking.system.commons.infrastructure.messaging.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Fixa a escolha de herdar do {@code AbstractRabbitPublisher}: o publisher do relay só serve a
 * {@code publishTo}, com o destino da linha, e recusa o {@code publish(Object)} herdado em vez
 * de enviá-lo a um exchange que não existe.
 */
@DisplayName("Publisher do relay do outbox")
class OutboxBrokerPublisherTest {

  private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
  private final OutboxBrokerPublisher publisher = new OutboxBrokerPublisher(this.rabbit);
  private final Object event = new Object();

  @Test
  @DisplayName("publica no exchange e na routing key gravados na linha")
  void publicaNoDestinoDaLinha() {
    doAnswer(invocation -> {
      invocation.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true, null));
      return null;
    }).when(this.rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class), any(CorrelationData.class));

    assertThatCode(() -> this.publisher.publishTo("exchange.da-linha", "routing-key.da-linha", this.event))
      .doesNotThrowAnyException();

    verify(this.rabbit).convertAndSend(
      eq("exchange.da-linha"), eq("routing-key.da-linha"), eq(this.event), any(CorrelationData.class)
    );
  }

  @Test
  @DisplayName("recusa o publish herdado, que não tem destino, sem tocar o broker")
  void recusaOPublishHerdado() {
    assertThatThrownBy(() -> this.publisher.publish(this.event))
      .isExactlyInstanceOf(UnsupportedOperationException.class);

    verifyNoInteractions(this.rabbit);
  }

}
