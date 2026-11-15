package com.hotel.booking.system.commons.core.application.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
   * Reserva até {@code limit} eventos publicáveis agora ({@code now}): de cada
   * {@code aggregateId}, só o mais antigo ainda pendente e não abandonado, e nenhum cujo
   * agregado esteja em {@code excludedAggregates}. Linha com {@code next_attempt_at} no futuro
   * espera; enquanto ela espera, as seguintes do mesmo agregado também esperam.
   */
  List<OutboxEntry> claimPending(int limit, Set<String> excludedAggregates, Instant now);

  void markPublished(UUID id, Instant publishedAt);

  /**
   * Registra uma falha de publicação. {@code abandonedAt} preenchido tira a linha do relay
   * de vez: tentar de novo não muda o resultado (tipo fora dos pacotes confiáveis, classe
   * ausente, ou nenhuma fila recebeu a mensagem). Vazio significa que a linha continua
   * pendente e {@code nextAttemptAt} diz quando tentar de novo.
   */
  void recordFailure(UUID id, int attempts, Optional<Instant> nextAttemptAt, Optional<Instant> abandonedAt);

  /**
   * @return quantas linhas publicadas foram removidas
   */
  int deletePublishedBefore(Instant threshold);

}
