package com.hotel.booking.system.commons.infrastructure.messaging.outbox;

import com.hotel.booking.system.commons.core.application.outbox.OutboxEntry;
import com.hotel.booking.system.commons.core.application.outbox.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

/**
 * Publica as linhas pendentes do outbox e só marca {@code published_at} depois que o broker
 * confirma a entrega. A marca e a reserva da linha estão na mesma transação: se o envio falha,
 * o rollback desfaz a marca e a linha continua pendente para a próxima rodada.
 *
 * <p>Um agregado cuja publicação falha nesta rodada sai do lote seguinte da mesma rodada. Sem
 * isso, a mensagem sem fila (o return da issue #3) seguraria a thread e impediria os outros
 * agregados de sair. A ordem dentro do agregado continua: o mais antigo pendente é o único
 * que {@link OutboxRepository#claimPending} devolve.</p>
 */
@Slf4j
public class OutboxRelay {

  private final OutboxRepository repository;

  private final OutboxBrokerPublisher broker;

  private final JsonMapper jsonMapper;

  private final TransactionTemplate transaction;

  private final Clock clock;

  private final int batchSize;

  public OutboxRelay(
    final OutboxRepository repository,
    final OutboxBrokerPublisher broker,
    final JsonMapper jsonMapper,
    final TransactionTemplate transaction,
    final Clock clock,
    final int batchSize
  ) {
    this.repository = repository;
    this.broker = broker;
    this.jsonMapper = jsonMapper;
    this.transaction = transaction;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  @Scheduled(
    fixedDelayString = "${app.outbox.relay-interval:PT1S}",
    initialDelayString = "${app.outbox.relay-interval:PT1S}"
  )
  public void publishPending() {
    final Set<String> failedAggregates = new HashSet<>();
    for (var published = 0; published < this.batchSize; published++) {
      if (!this.publishNext(failedAggregates)) {
        return;
      }
    }
  }

  /**
   * @return {@code false} quando não há mais linha publicável nesta rodada
   */
  private boolean publishNext(final Set<String> failedAggregates) {
    final var claimed = new OutboxEntry[1];
    try {
      final var found = this.transaction.execute(status -> {
        final var pending = this.repository.claimPending(1, failedAggregates);
        if (pending.isEmpty()) {
          return false;
        }
        final var entry = pending.getFirst();
        claimed[0] = entry;
        this.broker.publishTo(entry.exchange(), entry.routingKey(), this.deserialize(entry));
        this.repository.markPublished(entry.id(), this.clock.instant());
        return true;
      });
      return Boolean.TRUE.equals(found);
    } catch (final RuntimeException exception) {
      final var entry = claimed[0];
      if (entry != null) {
        failedAggregates.add(entry.aggregateId());
        log.warn(
          "Outbox event stayed pending after a failed publish | aggregateId={} | eventType={} | exchange={} | routingKey={}",
          entry.aggregateId(),
          entry.eventType(),
          entry.exchange(),
          entry.routingKey(),
          exception
        );
        return true;
      }
      throw exception;
    }
  }

  private Object deserialize(final OutboxEntry entry) {
    try {
      final var type = Class.forName(entry.eventType());
      return this.jsonMapper.readValue(entry.payload(), type);
    } catch (final ClassNotFoundException exception) {
      throw new IllegalStateException("Outbox event type is not on the classpath: " + entry.eventType(), exception);
    }
  }

  int batchSize() {
    return this.batchSize;
  }

}
