package com.hotel.booking.system.commons.infrastructure.messaging.idempotency;

import com.hotel.booking.system.commons.core.application.idempotency.ProcessedEventRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Idempotência de consumo com o registro na memória da JVM, para o serviço sem banco. As
 * limitações dessa escolha estão em {@link InMemoryProcessedEventRepository}.
 */
@Configuration(proxyBeanMethods = false)
@Import(IdempotencyConfiguration.class)
public class InMemoryIdempotencyConfiguration {

  @Bean
  public ProcessedEventRepository processedEventRepository() {
    return new InMemoryProcessedEventRepository();
  }

}
