package com.hotel.booking.system.hotel.service.core.application.dto;

import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record RegisterHotelRoomInput(
  @NotBlank(message = ApplicationMessage.HOTEL_ROOM_NAME_NOT_NULL)
  String name,
  @NotBlank(message = ApplicationMessage.HOTEL_ROOM_DESCRIPTION_NOT_NULL)
  String description,
  @NotNull(message = ApplicationMessage.HOTEL_ROOM_CAPACITY_NOT_NULL)
  @Min(value = 1, message = ApplicationMessage.HOTEL_ROOM_CAPACITY_INVALID)
  Integer capacity,
  @NotNull(message = ApplicationMessage.HOTEL_ROOM_CURRENT_PRICE_NOT_NULL)
  @Positive(message = ApplicationMessage.HOTEL_ROOM_CURRENT_PRICE_INVALID)
  BigDecimal currentPrice,
  @NotNull(message = ApplicationMessage.HOTEL_ROOM_QUANTITY_NOT_NULL)
  @Min(value = 1, message = ApplicationMessage.HOTEL_ROOM_QUANTITY_INVALID)
  Integer quantity
) {
}
