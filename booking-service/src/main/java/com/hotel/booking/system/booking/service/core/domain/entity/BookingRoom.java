package com.hotel.booking.system.booking.service.core.domain.entity;

import com.hotel.booking.system.booking.service.core.domain.exception.BookingDomainException;
import com.hotel.booking.system.booking.service.core.domain.valueobject.BookingId;
import com.hotel.booking.system.booking.service.core.domain.valueobject.BookingRoomId;
import com.hotel.booking.system.commons.core.domain.AbstractDomainEntity;
import com.hotel.booking.system.commons.core.domain.valueobject.Money;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.Optional;

@SuperBuilder
public class BookingRoom extends AbstractDomainEntity<BookingRoomId> {

  private RoomId roomId;
  private BookingId bookingId;
  private Integer quantity;
  private Money price;


  public BookingRoom(
    final BookingRoomId id,
    final RoomId roomId,
    final BookingId bookingId,
    final Integer quantity,
    final Money price
  ) {
    super(id);
    this.roomId = roomId;
    this.bookingId = bookingId;
    this.quantity = quantity;
    this.price = price;
  }

  public void initialize(final BookingId bookingId) {
    this.setId(BookingRoomId.newInstance());
    this.bookingId = bookingId;
  }

  /**
   * Recusa a linha cuja quantidade não existe ou não é positiva.
   *
   * <p>A disponibilidade soma {@code quantity}, então o sinal dela decide quantas vagas sobram:
   * uma linha de {@code -3} gravada como {@code PENDING} libera três vagas para as reservas
   * seguintes do quarto. O total do pedido não barra isso, porque {@code preço × quantidade}
   * leva o mesmo sinal nos dois lados da comparação.</p>
   */
  public void validate() {
    Optional.ofNullable(this.quantity)
      .filter(units -> units > 0)
      .orElseThrow(() -> new BookingDomainException(ApplicationMessage.BOOKING_ROOM_QUANTITY_INVALID));
  }

  public Money getPrice() {
    return this.price;
  }

  public Integer getQuantity() {
    return this.quantity;
  }

  public Money getTotalPrice() {
    return this.price.multiply(new BigDecimal(this.quantity));
  }

  public RoomId getRoomId() {
    return this.roomId;
  }
}
