package com.hotel.booking.system.commons.infrastructure.messaging.outbox;

import com.hotel.booking.system.commons.core.application.outbox.Outbox;
import com.hotel.booking.system.commons.core.application.outbox.OutboxEntry;
import com.hotel.booking.system.commons.core.application.outbox.OutboxRepository;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;
import java.util.UUID;

/**
 * Serializa o evento com o mesmo {@link JsonMapper} que o conversor AMQP usa, para o relay
 * conseguir reconstruir a classe concreta e o {@code __TypeId__} sair igual ao de um publish
 * direto.
 */
public final class JacksonOutbox implements Outbox {

  private final OutboxRepository repository;

  private final JsonMapper jsonMapper;

  public JacksonOutbox(final OutboxRepository repository, final JsonMapper jsonMapper) {
    this.repository = repository;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void append(
    final String aggregateId,
    final String exchange,
    final String routingKey,
    final Object event
  ) {
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(exchange, "exchange");
    Objects.requireNonNull(routingKey, "routingKey");
    Objects.requireNonNull(event, "event");
    this.repository.append(new OutboxEntry(
      UUID.randomUUID(),
      aggregateId,
      event.getClass().getName(),
      exchange,
      routingKey,
      this.jsonMapper.writeValueAsString(event),
      0L
    ));
  }

}
