package com.hotel.booking.system.commons.infrastructure.messaging.idempotency;

import com.hotel.booking.system.commons.core.application.idempotency.ProcessedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

/**
 * Apaga os registros de eventos processados que já passaram da janela de reentrega.
 * <p>
 * O registro só precisa sobreviver enquanto o broker ainda puder entregar a mensagem de novo.
 * Sem o expurgo a tabela cresce sem limite, uma linha por mensagem consumida. A retenção é de
 * {@code app.idempotency.retention} (sete dias por padrão) e a execução, a cada
 * {@code app.idempotency.purge-interval} (uma hora, em ISO-8601). Com várias instâncias do
 * serviço o job roda em todas, o que é inofensivo: o {@code DELETE} por data é idempotente.
 */
@Slf4j
public class ProcessedEventPurgeJob {

  private final ProcessedEventRepository repository;

  private final Duration retention;

  private final Clock clock;

  public ProcessedEventPurgeJob(final ProcessedEventRepository repository, final Duration retention, final Clock clock) {
    this.repository = repository;
    this.retention = retention;
    this.clock = clock;
  }

  @Scheduled(
    fixedDelayString = "${app.idempotency.purge-interval:PT1H}",
    initialDelayString = "${app.idempotency.purge-interval:PT1H}"
  )
  public void purge() {
    final var removed = this.repository.deleteRegisteredBefore(this.clock.instant().minus(this.retention));
    if (removed > 0) {
      log.info("Purged processed events older than {} | removed={}", this.retention, removed);
    }
  }

  public Duration retention() {
    return this.retention;
  }

}
