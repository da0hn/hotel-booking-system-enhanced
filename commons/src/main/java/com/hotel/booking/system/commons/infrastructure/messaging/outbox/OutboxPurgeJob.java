package com.hotel.booking.system.commons.infrastructure.messaging.outbox;

import com.hotel.booking.system.commons.core.application.outbox.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

/**
 * Remove eventos já publicados e mais velhos que a retenção. Linha pendente não entra: o que
 * o broker ainda não confirmou não pode ser apagado.
 *
 * <p>A retenção é {@code app.outbox.retention} (um dia) e a execução, a cada
 * {@code app.outbox.purge-interval} (uma hora). Com várias instâncias o job roda em todas; o
 * {@code DELETE} por data é idempotente.</p>
 */
@Slf4j
public class OutboxPurgeJob {

  private final OutboxRepository repository;

  private final Duration retention;

  private final Clock clock;

  public OutboxPurgeJob(final OutboxRepository repository, final Duration retention, final Clock clock) {
    this.repository = repository;
    this.retention = retention;
    this.clock = clock;
  }

  @Scheduled(
    fixedDelayString = "${app.outbox.purge-interval:PT1H}",
    initialDelayString = "${app.outbox.purge-interval:PT1H}"
  )
  public void purge() {
    final var removed = this.repository.deletePublishedBefore(this.clock.instant().minus(this.retention));
    if (removed > 0) {
      log.info("Purged published outbox events older than {} | removed={}", this.retention, removed);
    }
  }

  public Duration retention() {
    return this.retention;
  }

}
