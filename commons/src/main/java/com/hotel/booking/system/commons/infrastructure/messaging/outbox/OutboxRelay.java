package com.hotel.booking.system.commons.infrastructure.messaging.outbox;

import com.hotel.booking.system.commons.core.application.exception.MessageUnroutableException;
import com.hotel.booking.system.commons.core.application.outbox.OutboxEntry;
import com.hotel.booking.system.commons.core.application.outbox.OutboxRepository;
import com.hotel.booking.system.commons.core.domain.event.TrustedEventPackages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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

  /**
   * A partir da 10ª falha de transporte o atraso fica neste teto. Antes dele o backoff é
   * 1s, 2s, 4s… até 256s; 2^9 já passa de cinco minutos, então o teto vale de fato.
   */
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

  private final OutboxRepository repository;

  private final OutboxBrokerPublisher broker;

  private final JsonMapper jsonMapper;

  private final TransactionTemplate transaction;

  private final Clock clock;

  private final Duration roundBudget;

  public OutboxRelay(
    final OutboxRepository repository,
    final OutboxBrokerPublisher broker,
    final JsonMapper jsonMapper,
    final TransactionTemplate transaction,
    final Clock clock,
    final Duration roundBudget
  ) {
    this.repository = repository;
    this.broker = broker;
    this.jsonMapper = jsonMapper;
    this.transaction = transaction;
    this.clock = clock;
    this.roundBudget = roundBudget;
  }

  /**
   * Publica até esvaziar a fila ou estourar o orçamento da rodada. O
   * {@code relay-interval} só separa rodadas: com a fila vazia é a espera até a próxima
   * olhada; com backlog, a rodada seguinte começa assim que esta devolve a thread.
   */
  @Scheduled(
    fixedDelayString = "${app.outbox.relay-interval:PT1S}",
    initialDelayString = "${app.outbox.relay-interval:PT1S}"
  )
  public void publishPending() {
    final var invocationEnds = this.clock.instant().plus(this.roundBudget.multipliedBy(12));
    while (this.clock.instant().isBefore(invocationEnds)) {
      final var roundEnds = this.clock.instant().plus(this.roundBudget);
      final Set<String> failedAggregates = new HashSet<>();
      var progressed = false;
      while (this.clock.instant().isBefore(roundEnds)) {
        if (!this.publishNext(failedAggregates)) {
          return;
        }
        progressed = true;
      }
      if (!progressed) {
        return;
      }
    }
  }

  /**
   * @return {@code false} quando não há mais linha publicável nesta rodada
   */
  private boolean publishNext(final Set<String> failedAggregates) {
    final var claimed = new AtomicReference<OutboxEntry>();
    try {
      final var found = this.transaction.execute(status -> {
        final var pending = this.repository.claimPending(1, failedAggregates, this.clock.instant());
        if (pending.isEmpty()) {
          return false;
        }
        final var entry = pending.getFirst();
        claimed.set(entry);
        this.broker.publishTo(entry.exchange(), entry.routingKey(), this.deserialize(entry));
        this.repository.markPublished(entry.id(), this.clock.instant());
        return true;
      });
      return Boolean.TRUE.equals(found);
    } catch (final RuntimeException exception) {
      final var entry = Optional.ofNullable(claimed.get());
      if (entry.isEmpty()) {
        throw exception;
      }
      entry.ifPresent(value -> {
        failedAggregates.add(value.aggregateId());
        this.recordFailure(value, exception);
      });
      return true;
    }
  }

  /**
   * Falha da própria linha (tipo fora dos pacotes, classe ausente, nenhuma fila) abandona
   * na hora: tentar de novo não muda o resultado. Falha de transporte só empurra o
   * {@code next_attempt_at}. A linha continua pendente até o broker voltar, e enquanto
   * espera segura os eventos seguintes do mesmo agregado.
   */
  private void recordFailure(final OutboxEntry entry, final RuntimeException exception) {
    final var attempts = entry.attempts() + 1;
    final var now = this.clock.instant();
    final var causedByRow = exception instanceof IllegalStateException
      || exception instanceof MessageUnroutableException;
    final Optional<Instant> abandonedAt = causedByRow ? Optional.of(now) : Optional.empty();
    final Optional<Instant> nextAttemptAt = causedByRow ? Optional.empty() : Optional.of(now.plus(this.backoff(attempts)));
    this.transaction.executeWithoutResult(status ->
      this.repository.recordFailure(entry.id(), attempts, nextAttemptAt, abandonedAt)
    );
    if (causedByRow) {
      log.error(
        "Outbox event abandoned | aggregateId={} | eventType={} | reason={}",
        entry.aggregateId(),
        entry.eventType(),
        exception.getMessage()
      );
      return;
    }
    log.warn(
      "Outbox event stayed pending after a failed publish | aggregateId={} | eventType={} | attempt={} | reason={}",
      entry.aggregateId(),
      entry.eventType(),
      attempts,
      exception.getMessage()
    );
  }

  private Duration backoff(final int attempts) {
    final var exponent = attempts - 1;
    if (exponent >= 9) {
      return MAX_BACKOFF;
    }
    return Duration.ofSeconds(1L << exponent);
  }

  private Object deserialize(final OutboxEntry entry) {
    this.requireTrusted(entry.eventType());
    try {
      final var type = Class.forName(entry.eventType());
      return this.jsonMapper.readValue(entry.payload(), type);
    } catch (final ClassNotFoundException exception) {
      throw new IllegalStateException("Outbox event type is not on the classpath: " + entry.eventType(), exception);
    }
  }

  /**
   * A mesma regra do {@code __TypeId__} que chega do broker: só um pacote listado em
   * {@link TrustedEventPackages} pode ser instanciado, por igualdade, não por prefixo.
   * A checagem vem antes do {@code Class.forName}, que executaria o inicializador estático
   * de qualquer classe do classpath.
   */
  private void requireTrusted(final String eventType) {
    final var separator = eventType.lastIndexOf('.');
    final var packageName = separator < 0 ? "" : eventType.substring(0, separator);
    final var trusted = Arrays.stream(TrustedEventPackages.names()).anyMatch(packageName::equals);
    if (!trusted) {
      throw new IllegalStateException("Outbox event type is not in a trusted package: " + eventType);
    }
  }

  Duration roundBudget() {
    return this.roundBudget;
  }

}
