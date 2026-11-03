package com.hotel.booking.system.commons.infrastructure.messaging.idempotency;

import com.hotel.booking.system.commons.core.application.idempotency.IdempotentEventConsumer;
import com.hotel.booking.system.commons.core.application.idempotency.ProcessedEventRepository;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

/**
 * As peças da idempotência que não dependem de onde o registro mora: o consumidor, o job de
 * expurgo e o agendador. Quem importa é uma das variantes — {@link JdbcIdempotencyConfiguration}
 * ou {@link InMemoryIdempotencyConfiguration} —, que fornece o {@link ProcessedEventRepository}.
 * <p>
 * A variante é escolhida por import explícito em cada serviço, e não por
 * {@code @ConditionalOnBean}: numa configuração importada a condição é avaliada antes de o
 * {@code JdbcClient} existir, e o resultado dependeria da ordem de processamento.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class IdempotencyConfiguration {

  /**
   * Quanto tempo um {@code eventId} registrado é lembrado. Precisa exceder a maior janela em que
   * a mensagem ainda pode ser reentregue, inclusive a de uma DLQ reprocessada à mão.
   */
  static final String RETENTION_PROPERTY = "app.idempotency.retention";

  static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

  @Bean
  public IdempotentEventConsumer idempotentEventConsumer(final ProcessedEventRepository repository) {
    return new IdempotentEventConsumer(repository);
  }

  /**
   * A retenção é lida pelo {@link Binder}, e não por {@code @Value}: o {@code @Value} só
   * converte "7d" ou "36h" em {@link Duration} se o contexto tiver o conversor do Boot, e o
   * {@code Binder} traz o dele consigo.
   */
  @Bean
  public ProcessedEventPurgeJob processedEventPurgeJob(
    final ProcessedEventRepository repository,
    final Environment environment
  ) {
    final var retention = Binder.get(environment)
      .bind(RETENTION_PROPERTY, Duration.class)
      .orElse(DEFAULT_RETENTION);
    return new ProcessedEventPurgeJob(repository, retention, Clock.systemUTC());
  }

}
