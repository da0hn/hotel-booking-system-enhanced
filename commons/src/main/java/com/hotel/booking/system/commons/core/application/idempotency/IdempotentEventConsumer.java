package com.hotel.booking.system.commons.core.application.idempotency;

import com.hotel.booking.system.commons.core.domain.event.Event;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Executa o processamento de um evento no máximo uma vez por {@code eventId}.
 * <p>
 * O RabbitMQ entrega ao menos uma vez: ack perdido, reconexão e redeploy durante o
 * processamento fazem a mesma mensagem chegar de novo. O listener passa o evento e o efeito para
 * cá; na reentrega o efeito não roda e o listener retorna normalmente, o que confirma a
 * mensagem.
 * <p>
 * <strong>O listener precisa ser transacional</strong> nos serviços com banco. O registro do
 * {@code eventId} entra na transação do listener, e é o commit único que torna a deduplicação
 * confiável (ver {@link ProcessedEventRepository}). O registro vem <em>antes</em> do efeito de
 * propósito: é a chave primária do banco que faz a segunda de duas entregas simultâneas esperar
 * a primeira terminar, em vez de executar o efeito junto com ela.
 */
@Slf4j
public class IdempotentEventConsumer {

  private final ProcessedEventRepository repository;

  public IdempotentEventConsumer(final ProcessedEventRepository repository) {
    this.repository = repository;
  }

  /**
   * @param event      o evento recebido
   * @param processing o efeito a aplicar, que roda só se o evento for novo
   * @return {@code true} se o efeito rodou; {@code false} se o evento era uma reentrega e foi
   * descartado
   */
  public boolean consume(final Event event, final Runnable processing) {
    final var eventId = Optional.ofNullable(event.getEventId()).filter(id -> !id.isBlank());
    if (eventId.isEmpty()) {
      log.warn(
        "Event without eventId (legacy message), processing without deduplication | type={}",
        event.getClass().getName()
      );
      processing.run();
      return true;
    }
    final var id = eventId.get();
    if (!this.repository.register(id, event.getClass().getName())) {
      log.info("Event already processed, discarding redelivery | eventId={} | type={}", id, event.getClass().getName());
      return false;
    }
    try {
      processing.run();
    }
    catch (final RuntimeException | Error e) {
      this.repository.release(id);
      throw e;
    }
    return true;
  }

}
