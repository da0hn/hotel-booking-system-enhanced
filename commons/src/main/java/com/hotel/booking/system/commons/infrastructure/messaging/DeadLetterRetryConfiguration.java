package com.hotel.booking.system.commons.infrastructure.messaging;

import com.hotel.booking.system.commons.core.domain.exception.UnsupportedEventTypeException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * O que acontece com uma mensagem cujo listener falhou: quantas vezes ela é tentada, o que
 * nunca é tentado de novo e para onde vai no fim.
 * <p>
 * O número de tentativas e o backoff vêm do {@code spring.rabbitmq.listener.simple.retry.*} de
 * cada serviço, e esta classe acrescenta as duas decisões que o YAML não expressa: a
 * classificação das falhas e o destino final. Cada serviço a importa com {@code @Import} — o
 * {@code commons} não é varrido pelo component scan dos serviços.
 */
@Configuration(proxyBeanMethods = false)
public class DeadLetterRetryConfiguration {

  private static final SpelExpressionParser PARSER = new SpelExpressionParser();

  /**
   * Esgotadas as tentativas, republica a mensagem original no exchange de dead-letter com a
   * routing key da DLQ da fila de onde ela veio, e dá ack na original. É o que acrescenta os
   * headers {@code x-exception-message}, {@code x-exception-stacktrace},
   * {@code x-original-exchange} e {@code x-original-routingKey}: o recoverer padrão do Boot
   * apenas rejeita a mensagem, e o RabbitMQ não registra a exceção que a causou.
   * <p>
   * O stack trace entra porque o {@code x-exception-message} traz só a mensagem da causa
   * direta, e uma exceção embrulhada (como a do listener do {@code customer-service}) perde
   * assim o motivo real. O recoverer o trunca ao {@code frame_max} do broker.
   */
  @Bean
  public MessageRecoverer deadLetterMessageRecoverer(final RabbitTemplate rabbitTemplate) {
    final var recoverer = new RepublishMessageRecoverer(
      rabbitTemplate,
      new LiteralExpression(DeadLetterTopology.EXCHANGE),
      PARSER.parseExpression("messageProperties.consumerQueue + '" + DeadLetterTopology.QUEUE_SUFFIX + "'")
    );
    recoverer.includeStackTrace(true);
    return recoverer;
  }

  @Bean
  public RabbitListenerRetrySettingsCustomizer deadLetterRetrySettingsCustomizer() {
    return settings -> settings.setExceptionPredicate(DeadLetterRetryConfiguration::isRetryable);
  }

  /**
   * Uma falha é definitiva quando nenhuma tentativa nova pode mudar o resultado: a mensagem
   * não se desserializa, ou o handler não trata o subtipo que recebeu. Todas as outras são
   * tratadas como transitórias, inclusive a falha de negócio dentro do handler — nesse caso o
   * custo de tentar de novo é pequeno, e o de descartar uma mensagem recuperável é alto.
   * <p>
   * A exceção chega embrulhada em {@code ListenerExecutionFailedException}, e o listener do
   * {@code customer-service} embrulha mais uma vez em {@code IllegalStateException}, então a
   * busca percorre a cadeia de causas e não olha só o tipo de cima.
   */
  static boolean isRetryable(final Throwable failure) {
    return ExceptionUtils.indexOfType(failure, MessageConversionException.class) < 0
      && ExceptionUtils.indexOfType(failure, UnsupportedEventTypeException.class) < 0;
  }

}
