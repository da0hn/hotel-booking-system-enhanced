package com.hotel.booking.system.commons.infrastructure.messaging;

import com.hotel.booking.system.commons.core.application.exception.MessagePublishException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Fixa o contrato de entrega do publisher: só retorna normalmente se o broker confirmou a
 * mensagem e a roteou para alguma fila. Qualquer outro desfecho vira {@link MessagePublishException}.
 *
 * <p>O {@link RabbitTemplate} é um mock, então o desfecho que o broker daria é simulado
 * completando o {@link CorrelationData} que o publisher envia junto da mensagem.</p>
 */
@DisplayName("Publisher RabbitMQ abstrato")
class AbstractRabbitPublisherTest {

  private static final String EXCHANGE = "exchange.test";
  private static final String ROUTING_KEY = "routing-key.test";
  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(50);

  private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
  private final TestEvent event = new TestEvent();
  private final TestPublisher publisher = new TestPublisher(this.rabbit, SHORT_TIMEOUT);

  @Test
  @DisplayName("publica no exchange e na routing key configurados quando o broker confirma")
  void publicaQuandoOBrokerConfirma() {
    this.brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

    assertThatCode(() -> this.publisher.publish(this.event)).doesNotThrowAnyException();

    verify(this.rabbit).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), eq(this.event), any(CorrelationData.class));
  }

  @Test
  @DisplayName("lança MessagePublishException quando o broker recusa a mensagem (nack)")
  void lancaQuandoOBrokerRecusa() {
    this.brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "queue full")));

    assertThatThrownBy(() -> this.publisher.publish(this.event))
      .isInstanceOf(MessagePublishException.class)
      .hasMessageContaining("TestEvent")
      .hasMessageContaining(EXCHANGE)
      .hasMessageContaining(ROUTING_KEY)
      .hasMessageContaining("queue full");
  }

  @Test
  @DisplayName("lança MessagePublishException quando nenhuma fila recebeu a mensagem (return)")
  void lancaQuandoNenhumaFilaRecebeu() {
    this.brokerAnswers(correlation -> {
      correlation.setReturned(new ReturnedMessage(
        mock(Message.class), 312, "NO_ROUTE", EXCHANGE, ROUTING_KEY
      ));
      correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
    });

    assertThatThrownBy(() -> this.publisher.publish(this.event))
      .isInstanceOf(MessagePublishException.class)
      .hasMessageContaining("NO_ROUTE")
      .hasMessageContaining(EXCHANGE)
      .hasMessageContaining(ROUTING_KEY);
  }

  @Test
  @DisplayName("lança MessagePublishException quando o broker não responde dentro do prazo")
  void lancaQuandoOBrokerNaoResponde() {
    this.brokerAnswers(correlation -> {
    });

    assertThatThrownBy(() -> this.publisher.publish(this.event))
      .isInstanceOf(MessagePublishException.class)
      .hasMessageContaining("TestEvent")
      .hasMessageContaining(EXCHANGE)
      .hasMessageContaining(ROUTING_KEY);
  }

  @Test
  @DisplayName("lança MessagePublishException preservando a causa quando o broker está indisponível")
  void lancaPreservandoACausaQuandoOBrokerEstaIndisponivel() {
    final var cause = new AmqpConnectException(new IOException("connection refused"));
    doThrow(cause).when(this.rabbit)
      .convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), eq(this.event), any(CorrelationData.class));

    assertThatThrownBy(() -> this.publisher.publish(this.event))
      .isInstanceOf(MessagePublishException.class)
      .hasCause(cause)
      .hasMessageContaining(EXCHANGE)
      .hasMessageContaining(ROUTING_KEY);
  }

  @Test
  @DisplayName("lança MessagePublishException preservando a causa quando o envio falha por outro motivo")
  void lancaPreservandoACausaQuandoOEnvioFalhaPorOutroMotivo() {
    final var cause = new IllegalStateException("connection factory is closed");
    doThrow(cause).when(this.rabbit)
      .convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), eq(this.event), any(CorrelationData.class));

    assertThatThrownBy(() -> this.publisher.publish(this.event))
      .isInstanceOf(MessagePublishException.class)
      .hasCause(cause);
  }

  @Test
  @DisplayName("lança MessagePublishException e preserva a interrupção da thread")
  void lancaEPreservaAInterrupcaoDaThread() {
    this.brokerAnswers(correlation -> {
    });

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> this.publisher.publish(this.event))
        .isInstanceOf(MessagePublishException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private void brokerAnswers(final Consumer<CorrelationData> answer) {
    doAnswer(invocation -> {
      answer.accept(invocation.getArgument(3, CorrelationData.class));
      return null;
    }).when(this.rabbit).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), eq(this.event), any(CorrelationData.class));
  }

  private static final class TestEvent {
  }

  private static final class TestPublisher extends AbstractRabbitPublisher<TestEvent> {

    private TestPublisher(final RabbitTemplate rabbitTemplate, final Duration confirmTimeout) {
      super(rabbitTemplate, EXCHANGE, ROUTING_KEY, confirmTimeout);
    }
  }
}
