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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Cobre o ponto da saga em que o arredondamento de centavos derrubava a reserva.
 * <p>
 * {@code validateTotalPrice()} confronta dois números de origens diferentes: o total que veio
 * no {@code BookingRoomRequestedEvent}, calculado pelo hotel-service a partir do preço lido do
 * seu banco, e a soma dos itens que o próprio booking-service recompõe. Enquanto as colunas
 * monetárias tinham escala zero, esses dois caminhos arredondavam em momentos distintos e a
 * comparação podia falhar por um centavo — recusando a reserva com
 * {@code BOOKING_TOTAL_PRICE_INVALID} sem que houvesse nada de errado com ela.
 * <p>
 * O que está sendo travado aqui não é a aritmética do {@code Money}, que
 * {@code MoneyTest} já cobre, mas o fato de a decisão de negócio depender dela.
 */
@DisplayName("Validação do total da reserva")
class BookingTotalPriceValidationTest {

  private static final LocalDate CHECK_IN = LocalDate.now().plusDays(10);
  private static final LocalDate CHECK_OUT = CHECK_IN.plusDays(2);

  @Test
  @DisplayName("aceita o total quando ele bate com a soma dos itens com centavos")
  void aceitaTotalQueBateComASomaDosItensComCentavos() {
    final var booking = this.bookingComItem(new BigDecimal("199.99"), 2, new BigDecimal("399.98"));

    assertThatCode(booking::validate).doesNotThrowAnyException();
  }

  /**
   * O caso que a issue #1 apontou: o total chega do evento com a escala que o emissor escreveu
   * e a soma dos itens nasce com a escala das colunas. Antes de o {@code Money} normalizar a
   * escala e comparar por {@code compareTo}, {@code 342.5} e {@code 342.50} eram totais
   * diferentes para esta validação.
   */
  @Test
  @DisplayName("aceita o total quando ele difere da soma apenas na escala")
  void aceitaTotalQueDifereDaSomaApenasNaEscala() {
    final var booking = this.bookingComItem(new BigDecimal("342.50"), 1, new BigDecimal("342.5"));

    assertThatCode(booking::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("recusa o total quando ele diverge da soma dos itens de verdade")
  void recusaTotalQueDivergeDaSomaDosItens() {
    final var booking = this.bookingComItem(new BigDecimal("199.99"), 2, new BigDecimal("399.99"));

    assertThatExceptionOfType(BookingDomainException.class)
      .isThrownBy(booking::validate)
      .withMessage(ApplicationMessage.BOOKING_TOTAL_PRICE_INVALID);
  }

  private Booking bookingComItem(
    final BigDecimal precoDoItem,
    final Integer quantidade,
    final BigDecimal totalInformado
  ) {
    final var bookingId = BookingId.newInstance();
    return new Booking(
      bookingId,
      ReservationOrderId.newInstance(),
      CustomerId.newInstance(),
      BookingPeriod.of(CHECK_IN, CHECK_OUT),
      Money.of(totalInformado),
      List.of(
        new BookingRoom(
          BookingRoomId.newInstance(),
          RoomId.newInstance(),
          bookingId,
          quantidade,
          Money.of(precoDoItem)
        )
      ),
      quantidade,
      BookingStatus.PENDING
    );
  }
}
