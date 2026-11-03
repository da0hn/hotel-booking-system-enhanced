package com.hotel.booking.system.commons.core.domain.event;


import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public final class BookingRoomFailedEvent extends BookingRoomResponseEvent {

  @Builder.Default
  private final String eventId = Event.newEventId();
  private final Instant createdAt = Instant.now();
  private final String reservationOrderId;
  private final String customerId;
  private final LocalDate checkIn;
  private final LocalDate checkOut;
  private final List<String> failureMessages;
  private final CustomerReservationStatus status;

  @Override
  public String toString() {
    return ToStringBuilder.reflectionToString(this, ToStringStyle.JSON_STYLE);
  }

}
