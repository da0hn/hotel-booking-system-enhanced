package com.hotel.booking.system.commons.core.domain.event;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.util.List;

@Getter
@SuperBuilder
@Jacksonized
public final class BookingRoomPaymentFailed extends BookingRoomStatusUpdatedEvent {

  private final List<String> failureMessages;

  @Override
  public String toString() {
    return ToStringBuilder.reflectionToString(this, ToStringStyle.JSON_STYLE);
  }
}
