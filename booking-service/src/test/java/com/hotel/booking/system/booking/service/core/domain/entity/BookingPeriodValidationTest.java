package com.hotel.booking.system.booking.service.core.domain.entity;

import com.hotel.booking.system.booking.service.core.domain.exception.BookingDomainException;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Guarda a decisão do mantenedor sobre a estadia de zero noites (PR #48): o check-out precisa
 * cair depois do check-in.
 *
 * <p>A regra vive em {@link BookingPeriod#validate()} e <strong>não</strong> no construtor, de
 * propósito. O {@code BookingInitializer} só converte em falha limpa (um
 * {@code BookingRoomFailedEvent}) a exceção que sai de {@code Booking.validate()}; uma que
 * saísse do construtor, no mapper, subiria até o listener e iria para o retry e a DLQ. E o
 * construtor também reidrata reservas do banco: se ele recusasse, uma linha antiga de zero
 * noites faria toda checagem de disponibilidade do quarto lançar exceção.</p>
 */
@DisplayName("Validação do período da reserva")
class BookingPeriodValidationTest {

  private static final LocalDate CHECK_IN = LocalDate.of(2030, 1, 10);

  @ParameterizedTest(name = "recusa check-out {0} dia(s) depois do check-in")
  @DisplayName("recusa estadia de zero noites e check-out anterior ao check-in")
  @ValueSource(ints = {0, -1, -30})
  void recusaCheckOutIgualOuAnteriorAoCheckIn(final int dias) {
    assertThatExceptionOfType(BookingDomainException.class)
      .isThrownBy(() -> BookingPeriod.of(CHECK_IN, CHECK_IN.plusDays(dias)).validate())
      .withMessage(ApplicationMessage.BOOKING_PERIOD_CHECK_OUT_NOT_AFTER_CHECK_IN);
  }

  @Test
  @DisplayName("aceita uma noite")
  void aceitaUmaNoite() {
    assertThatCode(() -> BookingPeriod.of(CHECK_IN, CHECK_IN.plusDays(1)).validate()).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "constrói o período com check-out {0} dia(s) depois do check-in")
  @DisplayName("constrói o período inválido sem recusar, para reidratar o que o banco já tem")
  @ValueSource(ints = {0, -1})
  void construirNaoRecusa(final int dias) {
    assertThatCode(() -> BookingPeriod.of(CHECK_IN, CHECK_IN.plusDays(dias))).doesNotThrowAnyException();
  }

}
