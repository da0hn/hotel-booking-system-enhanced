package com.hotel.booking.system.hotel.service.core.application.dto;

import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Builder;

import java.util.List;

/**
 * Corpo de {@code POST /hotel}. As restrições só exigem presença: o formato do CEP e as demais regras
 * seguem no domínio ({@code HotelAddress}, {@code Hotel}), que responde {@code 422}. As chaves de
 * {@code message} são as mesmas que o domínio usa para o mesmo campo.
 */
@Builder
public record RegisterHotelInput(
  @NotBlank(message = ApplicationMessage.HOTEL_NAME_INVALID)
  String name,
  @NotBlank(message = ApplicationMessage.HOTEL_DESCRIPTION_INVALID)
  String description,
  @NotBlank(message = ApplicationMessage.HOTEL_CATEGORY_NOT_NULL)
  String categoryId,
  @NotBlank(message = ApplicationMessage.HOTEL_STREET_INVALID)
  String street,
  @NotBlank(message = ApplicationMessage.HOTEL_CEP_INVALID)
  String cep,
  @NotBlank(message = ApplicationMessage.HOTEL_LOCALITY_NOT_NULL)
  String localityId,
  @Valid
  @NotEmpty(message = ApplicationMessage.HOTEL_ROOMS_INVALID)
  List<RegisterHotelRoomInput> rooms
) {
}
