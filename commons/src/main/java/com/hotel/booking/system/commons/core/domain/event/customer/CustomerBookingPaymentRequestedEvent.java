package com.hotel.booking.system.commons.core.domain.event.customer;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

@Getter
@SuperBuilder
@Jacksonized
public final class CustomerBookingPaymentRequestedEvent extends CustomerBookingStatusUpdatedEvent {

  private final String bookingRoomId;

  @Override
  public String toString() {
    return ToStringBuilder.reflectionToString(this, ToStringStyle.JSON_STYLE);
  }
}
