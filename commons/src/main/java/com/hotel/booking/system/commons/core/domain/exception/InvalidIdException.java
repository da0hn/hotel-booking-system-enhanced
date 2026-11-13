package com.hotel.booking.system.commons.core.domain.exception;

import com.hotel.booking.system.commons.core.message.ApplicationMessage;

/**
 * Um id recebido na fronteira HTTP que não tem o formato de UUID.
 *
 * <p>Existe separada do {@link IllegalArgumentException} que o {@code UUID.fromString} lança por dois
 * motivos. O {@code GlobalExceptionHandler} não pode responder {@code 400} a qualquer
 * {@code IllegalArgumentException}: uma inesperada de verdade precisa continuar {@code 500}. E só quem
 * valida na entrada sabe o nome do campo, que o {@code data} do erro precisa listar; os value objects
 * de id, que os listeners também usam, não o sabem.</p>
 *
 * <p>A mensagem é só a chave {@link ApplicationMessage#REQUEST_ID_INVALID}: nem a exceção nem o log
 * carregam o valor enviado pelo cliente.</p>
 */
public class InvalidIdException extends RuntimeException {

  private final String field;

  public InvalidIdException(final String field) {
    super(ApplicationMessage.REQUEST_ID_INVALID);
    this.field = field;
  }

  public String getField() {
    return this.field;
  }
}
