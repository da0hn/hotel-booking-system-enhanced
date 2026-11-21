package com.hotel.booking.system.commons.infrastructure.messaging.outbox;

import com.hotel.booking.system.commons.core.application.outbox.Outbox;
import com.hotel.booking.system.commons.core.application.outbox.OutboxRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * Outbox no banco do serviço. O {@code hotel-service} e o {@code booking-service} importam esta
 * configuração: são eles que gravam estado e publicam evento na mesma transação. O
 * {@code payment-service} não tem banco e continua publicando direto pelo
 * {@code AbstractRabbitPublisher}; o {@code customer-service} só consome.
 *
 * <p>O agendador já nasce da configuração de idempotência, que estes serviços também importam.
 * O relay não liga outro.</p>
 */
@Configuration(proxyBeanMethods = false)
public class JdbcOutboxConfiguration {

  static final String RETENTION_PROPERTY = "app.outbox.retention";

  static final String ROUND_BUDGET_PROPERTY = "app.outbox.round-budget";

  static final Duration DEFAULT_RETENTION = Duration.ofDays(1);

  static final Duration DEFAULT_ROUND_BUDGET = Duration.ofSeconds(5);

  @Bean
  public OutboxRepository outboxRepository(final JdbcClient jdbcClient) {
    return new JdbcOutboxRepository(jdbcClient);
  }

  @Bean
  public Outbox outbox(final OutboxRepository repository, final JsonMapper jsonMapper) {
    return new JacksonOutbox(repository, jsonMapper);
  }

  @Bean
  public OutboxBrokerPublisher outboxBrokerPublisher(final RabbitTemplate rabbitTemplate) {
    return new OutboxBrokerPublisher(rabbitTemplate);
  }

  @Bean
  public OutboxRelay outboxRelay(
    final OutboxRepository repository,
    final OutboxBrokerPublisher broker,
    final JsonMapper jsonMapper,
    final PlatformTransactionManager transactionManager,
    final Environment environment
  ) {
    final var roundBudget = Binder.get(environment)
      .bind(ROUND_BUDGET_PROPERTY, Duration.class)
      .orElse(DEFAULT_ROUND_BUDGET);
    return new OutboxRelay(
      repository,
      broker,
      jsonMapper,
      new TransactionTemplate(transactionManager),
      Clock.systemUTC(),
      roundBudget
    );
  }

  @Bean
  public OutboxPurgeJob outboxPurgeJob(final OutboxRepository repository, final Environment environment) {
    final var retention = Binder.get(environment)
      .bind(RETENTION_PROPERTY, Duration.class)
      .orElse(DEFAULT_RETENTION);
    return new OutboxPurgeJob(repository, retention, Clock.systemUTC());
  }

}
