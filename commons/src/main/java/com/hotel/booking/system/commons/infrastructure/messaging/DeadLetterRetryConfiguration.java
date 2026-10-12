package com.hotel.booking.system.commons.infrastructure.messaging;

import com.hotel.booking.system.commons.core.domain.exception.UnsupportedEventTypeException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.time.Duration;

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

  /**
   * A chave do jitter. O Boot 4.1 aceita {@code spring.rabbitmq.listener.simple.retry.*} para
   * tentativas, intervalo, multiplicador e teto, mas não tem chave para o jitter, embora o
   * {@code RetryPolicySettings} o suporte. Uma chave desconhecida sob o prefixo do Boot seria
   * ignorada em silêncio e confundiria quem lê, então o jitter mora no namespace
   * {@code app.rabbitmq}, que é nosso, e é aplicado pelo customizer.
   */
  public static final String JITTER_PROPERTY = "app.rabbitmq.retry.jitter";

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

  /**
   * Acrescenta às configurações lidas pelo Boot o classificador de falhas e o jitter.
   * <p>
   * O jitter sorteia cada espera entre {@code intervalo - jitter} e {@code intervalo + jitter},
   * sem passar de baixo do intervalo inicial nem de cima do teto, e o multiplicador também age
   * sobre ele. Serve para que consumidores que falham juntos, como as várias instâncias de um
   * serviço diante do mesmo banco fora do ar, não voltem todos no mesmo instante. Sem a chave
   * a espera é exata, como era antes.
   */
  @Bean
  public RabbitListenerRetrySettingsCustomizer deadLetterRetrySettingsCustomizer(final Environment environment) {
    final var jitter = Binder.get(environment).bind(JITTER_PROPERTY, Duration.class).orElse(Duration.ZERO);
    return settings -> {
      settings.setExceptionPredicate(DeadLetterRetryConfiguration::isRetryable);
      settings.setJitter(jitter);
    };
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
