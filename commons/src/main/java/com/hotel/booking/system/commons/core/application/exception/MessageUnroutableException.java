package com.hotel.booking.system.commons.core.application.exception;

/**
 * O broker confirmou a mensagem e a devolveu: nenhuma fila tinha binding para a routing key.
 * Publicar de novo não cria a fila, então o relay do outbox abandona a linha.
 */
public class MessageUnroutableException extends MessagePublishException {

  public MessageUnroutableException(final String message) {
    super(message);
  }

}
