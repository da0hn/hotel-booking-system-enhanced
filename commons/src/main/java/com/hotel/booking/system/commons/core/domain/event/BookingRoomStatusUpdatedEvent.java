package com.hotel.booking.system.commons.core.domain.event;

import com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Getter
@SuperBuilder
@AllArgsConstructor
public abstract sealed class BookingRoomStatusUpdatedEvent implements Event permits BookingRoomPaymentCompleted, BookingRoomPaymentFailed {
  @Builder.Default
  private final String eventId = Event.newEventId();
  private final Instant createdAt = Instant.now();
  private final String reservationOrderId;
  private final String customerId;
  private final BookingStatus status;
}
