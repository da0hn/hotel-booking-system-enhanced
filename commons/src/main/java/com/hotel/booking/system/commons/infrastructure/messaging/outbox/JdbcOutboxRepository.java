package com.hotel.booking.system.commons.infrastructure.messaging.outbox;

import com.hotel.booking.system.commons.core.application.outbox.OutboxEntry;
import com.hotel.booking.system.commons.core.application.outbox.OutboxRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code outbox_event} no schema do serviço. O nome da tabela vai sem qualificador: o
 * {@code currentSchema} da conexão resolve, como em {@code processed_event}.
 *
 * <p>Não abre transação. A linha entra na transação de quem grava o efeito, e o
 * {@code for update skip locked} só trava o que a transação do relay já abriu.</p>
 */
public final class JdbcOutboxRepository implements OutboxRepository {

  private static final String CLAIM = """
    select id, aggregate_id, event_type, exchange, routing_key, payload::text as payload, seq, attempts
    from outbox_event
    where published_at is null
      and abandoned_at is null
      and (next_attempt_at is null or next_attempt_at <= :now)
      and not exists (
        select 1 from outbox_event earlier
        where earlier.aggregate_id = outbox_event.aggregate_id
          and earlier.published_at is null
          and earlier.abandoned_at is null
          and earlier.seq < outbox_event.seq
      )
    """;

  private final JdbcClient jdbc;

  public JdbcOutboxRepository(final JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(final OutboxEntry entry) {
    this.jdbc.sql("""
        insert into outbox_event (id, aggregate_id, event_type, exchange, routing_key, payload)
        values (:id, :aggregateId, :eventType, :exchange, :routingKey, cast(:payload as jsonb))
        """)
      .param("id", entry.id())
      .param("aggregateId", entry.aggregateId())
      .param("eventType", entry.eventType())
      .param("exchange", entry.exchange())
      .param("routingKey", entry.routingKey())
      .param("payload", entry.payload())
      .update();
  }

  @Override
  public List<OutboxEntry> claimPending(final int limit, final Set<String> excludedAggregates, final Instant now) {
    final var nothingToExclude = excludedAggregates.isEmpty();
    final var sql = CLAIM
      + (nothingToExclude ? "" : "  and aggregate_id not in (:excluded)\n")
      + "order by seq\nlimit :limit\nfor update skip locked";
    var query = this.jdbc.sql(sql)
      .param("limit", limit)
      .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    if (!nothingToExclude) {
      query = query.param("excluded", excludedAggregates);
    }
    return query.query(JdbcOutboxRepository::map).list();
  }

  @Override
  public void markPublished(final UUID id, final Instant publishedAt) {
    this.jdbc.sql("update outbox_event set published_at = :publishedAt where id = :id")
      .param("id", id)
      .param("publishedAt", OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC))
      .update();
  }

  @Override
  public void recordFailure(
    final UUID id,
    final int attempts,
    final Optional<Instant> nextAttemptAt,
    final Optional<Instant> abandonedAt
  ) {
    this.jdbc.sql("""
        update outbox_event
        set attempts = :attempts,
            next_attempt_at = :nextAttemptAt,
            abandoned_at = :abandonedAt
        where id = :id
        """)
      .param("id", id)
      .param("attempts", attempts)
      .param("nextAttemptAt", nextAttemptAt.map(instant -> OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)).orElse(null))
      .param("abandonedAt", abandonedAt.map(instant -> OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)).orElse(null))
      .update();
  }

  @Override
  public int deletePublishedBefore(final Instant threshold) {
    return this.jdbc.sql("delete from outbox_event where published_at is not null and published_at < :threshold")
      .param("threshold", OffsetDateTime.ofInstant(threshold, ZoneOffset.UTC))
      .update();
  }

  private static OutboxEntry map(final ResultSet rs, final int row) throws SQLException {
    return new OutboxEntry(
      rs.getObject("id", UUID.class),
      rs.getString("aggregate_id"),
      rs.getString("event_type"),
      rs.getString("exchange"),
      rs.getString("routing_key"),
      rs.getString("payload"),
      rs.getLong("seq"),
      rs.getInt("attempts")
    );
  }

}
