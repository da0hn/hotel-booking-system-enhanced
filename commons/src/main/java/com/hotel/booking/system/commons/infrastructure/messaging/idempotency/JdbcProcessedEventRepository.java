package com.hotel.booking.system.commons.infrastructure.messaging.idempotency;

import com.hotel.booking.system.commons.core.application.idempotency.ProcessedEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Registro de eventos processados na tabela {@code processed_event} do schema do próprio
 * serviço.
 * <p>
 * O nome da tabela vai sem qualificador de schema: ele é resolvido pelo {@code search_path} da
 * conexão, que o {@code currentSchema} da URL de cada serviço aponta para o seu schema. É
 * por isso que uma única implementação serve {@code hotel}, {@code booking} e {@code customer}
 * sem que um enxergue a tabela do outro.
 * <p>
 * O {@code INSERT ... ON CONFLICT DO NOTHING} é de PostgreSQL e não é detalhe: com um
 * {@code save()} seguido de {@code catch} da violação de unicidade, a transação ficaria abortada
 * (<i>current transaction is aborted</i>) e o efeito do evento, que roda depois, falharia.
 * Contando as linhas afetadas, a duplicata é só um {@code 0} e a transação segue. Quando duas
 * entregas do mesmo evento chegam juntas, a segunda espera a primeira terminar: se ela confirma,
 * a segunda não insere nada; se desfaz, a segunda insere e processa.
 * <p>
 * Não é {@code @Transactional}: participa da transação do listener, e é essa transação única que
 * torna o registro e o efeito atômicos.
 */
public class JdbcProcessedEventRepository implements ProcessedEventRepository {

  private final JdbcClient jdbc;

  public JdbcProcessedEventRepository(final JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean register(final String eventId, final String eventType) {
    final var inserted = this.jdbc
      .sql("""
        insert into processed_event (event_id, event_type)
        values (:eventId, :eventType)
        on conflict (event_id) do nothing
        """)
      .param("eventId", eventId)
      .param("eventType", eventType)
      .update();
    return inserted == 1;
  }

  @Override
  public int deleteRegisteredBefore(final Instant threshold) {
    return this.jdbc
      .sql("delete from processed_event where processed_at < :threshold")
      .param("threshold", OffsetDateTime.ofInstant(threshold, ZoneOffset.UTC))
      .update();
  }

}
