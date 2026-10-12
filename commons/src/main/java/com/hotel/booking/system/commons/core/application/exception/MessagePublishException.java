package com.hotel.booking.system.commons.core.application.exception;

/**
 * Indica que um evento não chegou a nenhuma fila: o broker estava indisponível, recusou a
 * mensagem, não respondeu no prazo ou não tinha binding para a routing key.
 *
 * <p>É não verificada de propósito. Os publishers são portas de saída do {@code core}, e
 * quem os chama não tem como se recuperar localmente; o que importa é que a falha suba até
 * a fronteira transacional (o listener, que faz rollback) ou até a resposta HTTP, em vez
 * de ser engolida e deixar a saga parada sem sinal.</p>
 */
public class MessagePublishException extends RuntimeException {

  public MessagePublishException(final String message) {
    super(message);
  }

  public MessagePublishException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
