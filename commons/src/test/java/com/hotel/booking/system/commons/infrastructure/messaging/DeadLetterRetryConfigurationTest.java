package com.hotel.booking.system.commons.infrastructure.messaging;

import com.hotel.booking.system.commons.core.domain.event.BookingRoomResponseEvent;
import com.hotel.booking.system.commons.core.domain.exception.UnsupportedEventTypeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.listener.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.retry.RetryPolicySettings;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Classificação das falhas de listener para o retry")
class DeadLetterRetryConfigurationTest {

  private static final Message MENSAGEM = new Message(new byte[0], new MessageProperties());

  private static final UnsupportedEventTypeException SUBTIPO_SEM_TRATAMENTO =
    new UnsupportedEventTypeException(BookingRoomResponseEvent.class, new Object());

  static Stream<Arguments> falhasDeterministicas() {
    return Stream.of(
      arguments(
        "desserialização",
        new MessageConversionException("JSON inválido")
      ),
      arguments(
        "desserialização dentro do listener",
        new ListenerExecutionFailedException("falha", new MessageConversionException("JSON inválido"), MENSAGEM)
      ),
      arguments(
        "subtipo de evento sem tratamento",
        new ListenerExecutionFailedException("falha", SUBTIPO_SEM_TRATAMENTO, MENSAGEM)
      ),
      arguments(
        "subtipo sem tratamento embrulhado pelo listener do customer-service",
        new ListenerExecutionFailedException(
          "falha",
          new IllegalStateException("Failed to process CustomerBookingStatusUpdatedEvent", SUBTIPO_SEM_TRATAMENTO),
          MENSAGEM
        )
      )
    );
  }

  static Stream<Arguments> falhasTransitorias() {
    return Stream.of(
      arguments("exceção genérica", new RuntimeException("banco indisponível")),
      arguments("IllegalStateException sem causa", new IllegalStateException("Failed to process")),
      arguments(
        "falha de negócio dentro do listener",
        new ListenerExecutionFailedException("falha", new IllegalArgumentException("roomId inexistente"), MENSAGEM)
      )
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("falhasDeterministicas")
  @DisplayName("não retenta o que nenhuma tentativa nova resolve")
  void naoRetentaFalhaDeterministica(final String descricao, final Throwable falha) {
    assertThat(DeadLetterRetryConfiguration.isRetryable(falha)).isFalse();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("falhasTransitorias")
  @DisplayName("retenta as demais falhas")
  void retentaAsDemaisFalhas(final String descricao, final Throwable falha) {
    assertThat(DeadLetterRetryConfiguration.isRetryable(falha)).isTrue();
  }

  @Test
  @DisplayName("aplica o classificador às configurações de retry do listener")
  void aplicaOClassificadorAsConfiguracoesDeRetry() {
    final var settings = new RetryPolicySettings();

    new DeadLetterRetryConfiguration().deadLetterRetrySettingsCustomizer().customize(settings);

    assertThat(settings.getExceptionPredicate())
      .accepts(new RuntimeException("transitória"))
      .rejects(new MessageConversionException("JSON inválido"));
  }

}
