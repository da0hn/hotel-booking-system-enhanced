package com.hotel.booking.system.commons.infrastructure.messaging;

import com.hotel.booking.system.commons.core.application.exception.MessagePublishException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Base dos publishers RabbitMQ: o par exchange/routing key é a única coisa que muda entre
 * eles, e a garantia de entrega é a mesma para todos.
 *
 * <p>{@link #publish(Object)} só retorna se o broker <strong>confirmou</strong> a mensagem
 * e a <strong>roteou</strong> para alguma fila. Qualquer outro desfecho vira
 * {@link MessagePublishException}. O {@code convertAndSend} sozinho não dá essa garantia:
 * com {@code publisher-confirm-type: correlated} o ack chega de forma assíncrona, e um
 * {@code ConfirmCallback} de template só consegue registrar a falha, não fazê-la chegar a
 * quem chamou. Por isso cada envio leva um {@link CorrelationData} e o publisher espera o
 * futuro dele.</p>
 *
 * <p>Para que o broker devolva a mensagem sem fila, o serviço precisa de
 * {@code spring.rabbitmq.publisher-returns=true} e
 * {@code spring.rabbitmq.template.mandatory=true}; sem eles um evento sem binding é
 * confirmado e descartado em silêncio.</p>
 *
 * <p>O tipo do evento não é limitado por {@code Event} de propósito: nem todo evento do
 * contrato implementa a interface (o {@code EventContractTest} registra os casos), e prender
 * os publishers a ela exigiria alterar classes que trafegam entre serviços.</p>
 *
 * @param <T> o evento publicado por esta implementação
 */
@Slf4j
public abstract class AbstractRabbitPublisher<T> {

  private static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofSeconds(5);

  private final RabbitTemplate rabbitTemplate;
  private final String exchange;
  private final String routingKey;
  private final Duration confirmTimeout;

  protected AbstractRabbitPublisher(
    final RabbitTemplate rabbitTemplate,
    final String exchange,
    final String routingKey
  ) {
    this(rabbitTemplate, exchange, routingKey, DEFAULT_CONFIRM_TIMEOUT);
  }

  protected AbstractRabbitPublisher(
    final RabbitTemplate rabbitTemplate,
    final String exchange,
    final String routingKey,
    final Duration confirmTimeout
  ) {
    this.rabbitTemplate = rabbitTemplate;
    this.exchange = exchange;
    this.routingKey = routingKey;
    this.confirmTimeout = confirmTimeout;
  }

  public void publish(final T event) {
    log.info(
      "Publishing event: {} to exchange={} | routingKey={}",
      event,
      this.exchange,
      this.routingKey
    );
    final var correlation = new CorrelationData(UUID.randomUUID().toString());
    final CorrelationData.Confirm confirm;
    try {
      this.rabbitTemplate.convertAndSend(this.exchange, this.routingKey, event, correlation);
      confirm = correlation.getFuture().get(this.confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (final TimeoutException exception) {
      throw this.failure(event, "no broker confirmation within " + this.confirmTimeout, exception);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw this.failure(event, "interrupted while waiting for the broker confirmation", exception);
    } catch (final ExecutionException exception) {
      throw this.failure(event, "broker confirmation failed", exception);
    } catch (final RuntimeException exception) {
      throw this.failure(event, "could not send the message", exception);
    }
    this.ensureDelivered(event, correlation, confirm);
    log.info(
      "Event: {} published successfully to exchange={} | routingKey={}",
      event,
      this.exchange,
      this.routingKey
    );
  }

  private void ensureDelivered(
    final T event,
    final CorrelationData correlation,
    final CorrelationData.Confirm confirm
  ) {
    final var returned = correlation.getReturned();
    if (returned != null) {
      throw this.failure(event, "no queue received the message: " + returned.getReplyText(), null);
    }
    if (!confirm.ack()) {
      throw this.failure(event, "broker refused the message: " + confirm.reason(), null);
    }
  }

  private MessagePublishException failure(final T event, final String reason, final Throwable cause) {
    final var message = "Failed to publish %s to exchange=%s | routingKey=%s: %s".formatted(
      event.getClass().getSimpleName(),
      this.exchange,
      this.routingKey,
      reason
    );
    return cause == null ? new MessagePublishException(message) : new MessagePublishException(message, cause);
  }
}
