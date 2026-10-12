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
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.boot.retry.RetryPolicySettings;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
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

    customizer(new MockEnvironment()).customize(settings);

    assertThat(settings.getExceptionPredicate())
      .accepts(new RuntimeException("transitória"))
      .rejects(new MessageConversionException("JSON inválido"));
  }

  @Test
  @DisplayName("aplica o jitter configurado às configurações de retry do listener")
  void aplicaOJitterConfigurado() {
    final var settings = configuracaoDosServicos();

    customizer(new MockEnvironment().withProperty(DeadLetterRetryConfiguration.JITTER_PROPERTY, "500ms"))
      .customize(settings);

    assertThat(settings.getJitter()).isEqualTo(Duration.ofMillis(500));
  }

  @Test
  @DisplayName("sem jitter configurado a espera continua exata")
  void semJitterAEsperaEExata() {
    final var settings = configuracaoDosServicos();

    customizer(new MockEnvironment()).customize(settings);

    assertThat(settings.getJitter()).isEqualTo(Duration.ZERO);
    assertThat(primeirasEsperas(settings, 50)).containsOnly(1000L);
  }

  @Test
  @DisplayName("sorteia cada espera dentro da faixa do jitter e não repete sempre o mesmo valor")
  void sorteiaAEsperaDentroDaFaixaDoJitter() {
    final var settings = configuracaoDosServicos();
    customizer(new MockEnvironment().withProperty(DeadLetterRetryConfiguration.JITTER_PROPERTY, "500ms"))
      .customize(settings);

    final var primeiras = primeirasEsperas(settings, 300);
    final var segundas = segundasEsperas(settings, 300);

    assertThat(primeiras).allSatisfy(espera -> assertThat(espera).isBetween(1000L, 1500L));
    assertThat(primeiras.stream().distinct().count()).isGreaterThan(1);
    assertThat(segundas).allSatisfy(espera -> assertThat(espera).isBetween(1000L, 3000L));
    assertThat(segundas.stream().distinct().count()).isGreaterThan(1);
  }

  private static RabbitListenerRetrySettingsCustomizer customizer(final MockEnvironment ambiente) {
    return new DeadLetterRetryConfiguration().deadLetterRetrySettingsCustomizer(ambiente);
  }

  /** Os mesmos valores que o Boot lê do {@code application.yml} dos serviços antes de chamar o customizer. */
  private static RetryPolicySettings configuracaoDosServicos() {
    final var settings = new RetryPolicySettings();
    settings.setMaxRetries(2L);
    settings.setDelay(Duration.ofSeconds(1));
    settings.setMultiplier(2.0);
    settings.setMaxDelay(Duration.ofSeconds(10));
    return settings;
  }

  private static List<Long> primeirasEsperas(final RetryPolicySettings settings, final int amostras) {
    final var politica = settings.createRetryPolicy();
    return IntStream.range(0, amostras)
      .mapToObj(i -> politica.getBackOff().start().nextBackOff())
      .toList();
  }

  private static List<Long> segundasEsperas(final RetryPolicySettings settings, final int amostras) {
    final var politica = settings.createRetryPolicy();
    return IntStream.range(0, amostras)
      .mapToObj(i -> {
        final var execucao = politica.getBackOff().start();
        execucao.nextBackOff();
        return execucao.nextBackOff();
      })
      .toList();
  }

}
