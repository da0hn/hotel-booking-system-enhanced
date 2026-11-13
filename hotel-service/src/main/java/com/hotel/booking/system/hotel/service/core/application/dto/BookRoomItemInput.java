package com.hotel.booking.system.hotel.service.core.application.dto;

import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record BookRoomItemInput(
  @NotBlank(message = ApplicationMessage.HOTEL_ROOM_NOT_NULL)
  String roomId,
  @NotNull(message = ApplicationMessage.HOTEL_ROOM_QUANTITY_NOT_NULL)
  @Min(value = 1, message = ApplicationMessage.HOTEL_ROOM_QUANTITY_INVALID)
  Integer roomQuantity
) {
}
