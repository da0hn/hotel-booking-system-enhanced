package com.hotel.booking.system.commons.core.application.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Porta da tabela {@code outbox_event} do schema do serviço.
 *
 * <p>Os métodos participam da transação de quem chama. {@link #claimPending} trava as linhas
 * com {@code for update skip locked}: duas instâncias do relay não publicam o mesmo evento,
 * e um evento de um agregado não sai antes do anterior que ainda está pendente.</p>
 */
public interface OutboxRepository {

  void append(OutboxEntry entry);

  /**
   * Reserva até {@code limit} eventos publicáveis: de cada {@code aggregateId}, só o mais
   * antigo ainda pendente, e nenhum cujo agregado esteja em {@code excludedAggregates}
   * (agregado que já falhou nesta rodada do relay e não pode ser tentado de novo até a próxima).
   */
  List<OutboxEntry> claimPending(int limit, Set<String> excludedAggregates);

  void markPublished(UUID id, Instant publishedAt);

  /**
   * @return quantas linhas publicadas foram removidas
   */
  int deletePublishedBefore(Instant threshold);

}
