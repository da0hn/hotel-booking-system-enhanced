package com.hotel.booking.system.commons.core.domain.event;

import com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.time.Instant;
import java.util.List;

/**
 * A reserva ficou {@code PENDING} além do prazo e o {@code booking-service} a cancelou.
 * Segue o mesmo caminho das outras respostas de reserva, para o {@code hotel-service} avisar
 * o cliente.
 */
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public final class BookingRoomExpiredEvent extends BookingRoomResponseEvent {

  @Builder.Default
  private final String eventId = Event.newEventId();
  private final Instant createdAt = Instant.now();
  private final String reservationOrderId;
  private final String customerId;
  private final BookingStatus status;
  private final List<String> failureMessages;

  @Override
  public String toString() {
    return ToStringBuilder.reflectionToString(this, ToStringStyle.JSON_STYLE);
  }

}
