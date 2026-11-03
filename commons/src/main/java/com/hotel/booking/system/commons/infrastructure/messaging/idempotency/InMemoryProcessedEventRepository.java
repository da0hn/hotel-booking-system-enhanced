package com.hotel.booking.system.commons.infrastructure.messaging.idempotency;

import com.hotel.booking.system.commons.core.application.idempotency.ProcessedEventRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro de eventos processados na memória da JVM, para o serviço que não tem banco.
 * <p>
 * É a escolha documentada para o {@code payment-service}: protege contra a reentrega que acontece
 * <em>sem</em> o processo reiniciar — ack perdido, reconexão do consumidor, timeout —, que é a
 * rotineira. Não protege quando o processo morre entre o efeito e o ack, porque o registro morre
 * com ele; ali só um armazenamento durável ou uma regra de negócio idempotente resolveria. O
 * efeito aqui é fake (sorteio de aprovação), então o custo de um banco não se paga.
 * <p>
 * Não é transacional, por isso o {@link #release(String)} desfaz de fato o registro quando o
 * processamento falha: sem ele a nova tentativa do retry acharia o evento já "processado" e o
 * descartaria com ack. A memória é limitada pelo expurgo do {@link ProcessedEventPurgeJob}.
 */
public class InMemoryProcessedEventRepository implements ProcessedEventRepository {

  private final ConcurrentHashMap<String, Instant> registeredAt = new ConcurrentHashMap<>();

  private final Clock clock;

  public InMemoryProcessedEventRepository() {
    this(Clock.systemUTC());
  }

  InMemoryProcessedEventRepository(final Clock clock) {
    this.clock = clock;
  }

  @Override
  public boolean register(final String eventId, final String eventType) {
    return this.registeredAt.putIfAbsent(eventId, this.clock.instant()) == null;
  }

  @Override
  public void release(final String eventId) {
    this.registeredAt.remove(eventId);
  }

  @Override
  public int deleteRegisteredBefore(final Instant threshold) {
    final var before = this.registeredAt.size();
    this.registeredAt.values().removeIf(registered -> registered.isBefore(threshold));
    return before - this.registeredAt.size();
  }

}
