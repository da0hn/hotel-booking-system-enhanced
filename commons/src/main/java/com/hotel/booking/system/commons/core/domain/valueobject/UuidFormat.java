package com.hotel.booking.system.commons.core.domain.valueobject;

import com.hotel.booking.system.commons.core.domain.exception.InvalidIdException;

import java.util.Optional;
import java.util.UUID;

/**
 * Confere, na entrada de um caso de uso HTTP, que um id recebido como texto tem o formato de UUID,
 * antes de qualquer consulta ou publicação.
 *
 * <p>Usa o próprio {@link UUID#fromString(String)} porque é ele que os value objects de id chamam
 * adiante: o que passa aqui não pode lançar lá, e o que ele aceita, como {@code 1-1-1-1-1}, segue para
 * a consulta. Nulo conta como malformado, para que um id ausente não vire {@code NullPointerException}
 * e {@code 500}.</p>
 */
public final class UuidFormat {

  private UuidFormat() {
  }

  /**
   * @param field nome do campo na requisição, que o {@code data} do erro {@code 400} lista
   * @param value o id recebido do cliente
   * @throws InvalidIdException se o valor é nulo ou não é um UUID
   */
  public static void requireValid(final String field, final String value) {
    final var valid = Optional.ofNullable(value).map(UuidFormat::isUuid).orElse(false);
    if (!valid) {
      throw new InvalidIdException(field);
    }
  }

  private static boolean isUuid(final String value) {
    try {
      UUID.fromString(value);
      return true;
    } catch (final IllegalArgumentException exception) {
      return false;
    }
  }
}
