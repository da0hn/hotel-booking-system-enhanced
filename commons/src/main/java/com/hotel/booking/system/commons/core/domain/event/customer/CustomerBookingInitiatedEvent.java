package com.hotel.booking.system.commons.core.domain.event.customer;

import com.hotel.booking.system.commons.core.domain.event.BookingRoomItemRepresentation;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@SuperBuilder
@Jacksonized
public final class CustomerBookingInitiatedEvent extends CustomerBookingStatusUpdatedEvent {

  private final String hotelId;
  private final BigDecimal totalPrice;
  private final Integer guests;
  private final LocalDate checkIn;
  private final LocalDate checkOut;
  private final List<BookingRoomItemRepresentation> rooms;

  @Override
  public String toString() {
    return ToStringBuilder.reflectionToString(this, ToStringStyle.JSON_STYLE, false, CustomerBookingStatusUpdatedEvent.class);
  }

}
