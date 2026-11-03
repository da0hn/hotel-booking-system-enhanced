package com.hotel.booking.system.commons.infrastructure.messaging.idempotency;

import com.hotel.booking.system.commons.core.application.idempotency.ProcessedEventRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Idempotência de consumo com o registro no banco do serviço. Cada serviço com banco a importa
 * com {@code @Import}, ao lado da {@code DeadLetterRetryConfiguration}, e precisa de uma migration
 * que crie a tabela {@code processed_event} no seu schema.
 */
@Configuration(proxyBeanMethods = false)
@Import(IdempotencyConfiguration.class)
public class JdbcIdempotencyConfiguration {

  @Bean
  public ProcessedEventRepository processedEventRepository(final JdbcClient jdbcClient) {
    return new JdbcProcessedEventRepository(jdbcClient);
  }

}
