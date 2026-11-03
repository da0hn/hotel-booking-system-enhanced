package com.hotel.booking.system.booking.service.core.domain.entity;

import com.hotel.booking.system.booking.service.core.domain.exception.BookingDomainException;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public class BookingPeriod {

  private static final DateTimeFormatter DD_MM_YYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private final LocalDate checkIn;
  private final LocalDate checkOut;

  private BookingPeriod(
    final LocalDate checkIn,
    final LocalDate checkOut
  ) {
    this.checkIn = checkIn;
    this.checkOut = checkOut;
  }

  public static BookingPeriod of(final LocalDate checkIn, final LocalDate checkOut) {
    Objects.requireNonNull(
      checkIn,
      ApplicationMessage.BOOKING_PERIOD_CHECK_IN_NOT_NULL
    );
    Objects.requireNonNull(
      checkOut,
      ApplicationMessage.BOOKING_PERIOD_CHECK_OUT_NOT_NULL
    );
    return new BookingPeriod(checkIn, checkOut);
  }

  /**
   * Recusa o período em que o check-out não cai depois do check-in: a estadia precisa de pelo
   * menos uma noite.
   *
   * <p>A regra vive aqui e não no construtor, de propósito. {@code Booking.validate()} chama este
   * método, e o {@code BookingInitializer} converte a exceção em uma falha limpa
   * ({@code BookingRoomFailedEvent}); uma exceção do construtor sairia do mapper e iria para o
   * retry e a DLQ. O construtor também reidrata reservas do banco, e uma linha antiga de zero
   * noites não pode fazer toda checagem de disponibilidade do quarto dela lançar exceção.</p>
   */
  public void validate() {
    if (!this.checkOut.isAfter(this.checkIn)) {
      throw new BookingDomainException(ApplicationMessage.BOOKING_PERIOD_CHECK_OUT_NOT_AFTER_CHECK_IN);
    }
  }

  public boolean isContainedIn(final BookingPeriod other) {
    final var containsOtherPeriod = this.checkIn.isBefore(other.checkIn) && this.checkOut.isAfter(other.checkOut);
    final var isTotallyContained = this.checkIn.isAfter(other.checkIn) && this.checkOut.isBefore(other.checkOut);
    final var isEqualCheckOut = this.checkOut.isEqual(other.checkOut);
    final var isEqualCheckIn = this.checkIn.isEqual(other.checkIn);
    final var isPartiallyContainedByCheckIn = this.checkIn.isAfter(other.checkIn) && this.checkIn.isBefore(other.checkOut);
    final var isPartiallyContainedByCheckOut = this.checkOut.isAfter(other.checkIn) && this.checkOut.isBefore(other.checkOut);
    return containsOtherPeriod
      || isTotallyContained
      || isPartiallyContainedByCheckIn
      || isPartiallyContainedByCheckOut
      || isEqualCheckIn
      || isEqualCheckOut;
  }


  public LocalDate getCheckIn() {
    return this.checkIn;
  }

  public LocalDate getCheckOut() {
    return this.checkOut;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || this.getClass() != o.getClass()) return false;

    final BookingPeriod that = (BookingPeriod) o;

    if (!this.checkIn.equals(that.checkIn)) return false;
    return this.checkOut.equals(that.checkOut);
  }

  @Override
  public int hashCode() {
    int result = this.checkIn.hashCode();
    result = 31 * result + this.checkOut.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return ToStringBuilder.reflectionToString(this, ToStringStyle.JSON_STYLE);
  }

  public String periodAsString() {
    return this.checkIn.format(DD_MM_YYYY) + " - " + this.checkOut.format(DD_MM_YYYY);
  }
}
