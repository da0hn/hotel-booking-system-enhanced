package com.hotel.booking.system.commons.core.domain.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A matriz inteira de transições da reserva: só {@code PENDING} sai do lugar, para
 * {@code CONFIRMED} ou {@code CANCELED}. Os estados finais não transicionam, nem para o mesmo
 * estado: a reentrega de um {@code PaymentCompleted} com {@code eventId} novo, que a
 * deduplicação por evento não barra, é um {@code CONFIRMED → CONFIRMED}.
 */
@DisplayName("Transições de status da reserva")
class BookingStatusTest {

  @ParameterizedTest(name = "{0} → {1}: {2}")
  @DisplayName("só PENDING transiciona, e só para CONFIRMED ou CANCELED")
  @CsvSource({
    "PENDING, PENDING, false",
    "PENDING, CONFIRMED, true",
    "PENDING, CANCELED, true",
    "CONFIRMED, PENDING, false",
    "CONFIRMED, CONFIRMED, false",
    "CONFIRMED, CANCELED, false",
    "CANCELED, PENDING, false",
    "CANCELED, CONFIRMED, false",
    "CANCELED, CANCELED, false",
  })
  void apenasPendenteTransiciona(final BookingStatus atual, final BookingStatus proximo, final boolean permitida) {
    assertThat(atual.canTransitionTo(proximo)).isEqualTo(permitida);
  }

}
