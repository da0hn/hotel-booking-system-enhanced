package com.hotel.booking.system.commons.infrastructure.messaging.idempotency;

import com.hotel.booking.system.commons.core.application.idempotency.IdempotentEventConsumer;
import com.hotel.booking.system.commons.core.application.idempotency.ProcessedEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Configuração da idempotência de consumo")
class IdempotencyConfigurationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner();

  @Test
  @DisplayName("a variante JDBC registra o repositório do banco, o consumidor e o expurgo")
  void varianteJdbcRegistraRepositorioConsumidorEExpurgo() {
    this.runner
      .withUserConfiguration(JdbcClientFalso.class, JdbcIdempotencyConfiguration.class)
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context).hasSingleBean(ProcessedEventRepository.class);
        assertThat(context.getBean(ProcessedEventRepository.class)).isInstanceOf(JdbcProcessedEventRepository.class);
        assertThat(context).hasSingleBean(IdempotentEventConsumer.class);
        assertThat(context).hasSingleBean(ProcessedEventPurgeJob.class);
      });
  }

  @Test
  @DisplayName("a variante em memória registra o repositório da JVM, o consumidor e o expurgo")
  void varianteEmMemoriaRegistraRepositorioConsumidorEExpurgo() {
    this.runner
      .withUserConfiguration(InMemoryIdempotencyConfiguration.class)
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBean(ProcessedEventRepository.class)).isInstanceOf(InMemoryProcessedEventRepository.class);
        assertThat(context).hasSingleBean(IdempotentEventConsumer.class);
        assertThat(context).hasSingleBean(ProcessedEventPurgeJob.class);
      });
  }

  @Test
  @DisplayName("lê o período de retenção de app.idempotency.retention")
  void leORetencaoDaPropriedade() {
    this.runner
      .withUserConfiguration(InMemoryIdempotencyConfiguration.class)
      .withPropertyValues("app.idempotency.retention=36h")
      .run(context -> assertThat(context.getBean(ProcessedEventPurgeJob.class).retention())
        .isEqualTo(Duration.ofHours(36)));
  }

  @Test
  @DisplayName("retém por sete dias quando nada é configurado")
  void retemPorSeteDiasPorPadrao() {
    this.runner
      .withUserConfiguration(InMemoryIdempotencyConfiguration.class)
      .run(context -> assertThat(context.getBean(ProcessedEventPurgeJob.class).retention())
        .isEqualTo(Duration.ofDays(7)));
  }

  @Configuration(proxyBeanMethods = false)
  static class JdbcClientFalso {

    @Bean
    JdbcClient jdbcClient() {
      return mock(JdbcClient.class);
    }

  }

}
