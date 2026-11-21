package com.hotel.booking.system.commons.core.application.outbox;

import java.util.UUID;

/**
 * Uma linha da tabela {@code outbox_event}: o evento já serializado e o destino que o
 * publicador resolveu na hora de gravar.
 *
 * <p>A {@code sequence} é a ordem de inserção. {@code created_at} não serve para isso: no
 * PostgreSQL {@code now()} é o instante de início da transação, então dois eventos gravados
 * juntos sairiam com o mesmo carimbo. O identificador é UUID e também não ordena.</p>
 *
 * @param sequence zero na gravação; quem lê a linha pendente recebe o valor que o banco atribuiu
 */
public record OutboxEntry(
  UUID id,
  String aggregateId,
  String eventType,
  String exchange,
  String routingKey,
  String payload,
  long sequence,
  int attempts
) {
}
