package com.hotel.booking.system.booking.service.core.domain.entity;

import com.hotel.booking.system.booking.service.core.domain.exception.BookingDomainException;
import com.hotel.booking.system.booking.service.core.domain.valueobject.BookingId;
import com.hotel.booking.system.booking.service.core.domain.valueobject.BookingRoomId;
import com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerId;
import com.hotel.booking.system.commons.core.domain.valueobject.Money;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Guarda o sinal da quantidade de cada linha de reserva (issue #5).
 *
 * <p>Depois que a disponibilidade passou a somar {@code quantity}, o sinal dela decide quantas
 * vagas sobram: uma linha de {@code -3} gravada como {@code PENDING} libera três vagas para as
 * reservas seguintes do mesmo quarto e período, e o overbooking volta. O total do pedido não
 * barra isso, porque o {@code hotel-service} calcula {@code preço × quantidade} com o mesmo
 * sinal e os dois números batem.</p>
 */
@DisplayName("Validação da quantidade de cada quarto da reserva")
class BookingRoomQuantityValidationTest {

  private static final LocalDate CHECK_IN = LocalDate.now().plusDays(10);

  private static final BigDecimal PRICE = new BigDecimal("100.0000");

  @ParameterizedTest(name = "recusa quantidade {0}")
  @DisplayName("recusa quantidade nula, zero ou negativa")
  @NullSource
  @ValueSource(ints = {0, -1, -3})
  void recusaQuantidadeNulaZeroOuNegativa(final Integer quantidade) {
    final var booking = this.bookingComQuantidade(quantidade);

    assertThatExceptionOfType(BookingDomainException.class)
      .isThrownBy(booking::validate)
      .withMessage(ApplicationMessage.BOOKING_ROOM_QUANTITY_INVALID);
  }

  @Test
  @DisplayName("aceita quantidade positiva")
  void aceitaQuantidadePositiva() {
    final var booking = this.bookingComQuantidade(1);

    assertThatCode(booking::validate).doesNotThrowAnyException();
  }

  private Booking bookingComQuantidade(final Integer quantidade) {
    final var bookingId = BookingId.newInstance();
    final var total = quantidade == null ? BigDecimal.ZERO : PRICE.multiply(new BigDecimal(quantidade));
    return new Booking(
      bookingId,
      ReservationOrderId.newInstance(),
      CustomerId.newInstance(),
      BookingPeriod.of(CHECK_IN, CHECK_IN.plusDays(2)),
      Money.of(total),
      List.of(new BookingRoom(BookingRoomId.newInstance(), RoomId.newInstance(), bookingId, quantidade, Money.of(PRICE))),
      2,
      BookingStatus.PENDING
    );
  }

}
