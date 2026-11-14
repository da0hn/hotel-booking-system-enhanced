package com.hotel.booking.system.hotel.service.core.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * Corpo de {@code POST /hotel/booking}. As restrições recusam a requisição na fronteira HTTP, com
 * {@code 400}, antes de qualquer caso de uso; o {@code message} de cada uma é uma chave de
 * {@link ApplicationMessage}, a mesma que o domínio usaria. A validação cruzada do período vale
 * {@code checkOut} posterior a {@code checkIn}, e a invariante de domínio {@code BookingPeriod} continua
 * valendo atrás dela.
 */
@Builder
public record BookingRoomInput(
  @NotBlank(message = ApplicationMessage.BOOKING_CUSTOMER_NOT_NULL)
  String customerId,
  @NotBlank(message = ApplicationMessage.HOTEL_NOT_NULL)
  String hotelId,
  @NotNull(message = ApplicationMessage.HOTEL_BOOKING_GUESTS_INVALID)
  @Min(value = 1, message = ApplicationMessage.HOTEL_BOOKING_GUESTS_INVALID)
  Integer guests,
  @NotNull(message = ApplicationMessage.BOOKING_PERIOD_CHECK_IN_NOT_NULL)
  @FutureOrPresent(message = ApplicationMessage.BOOKING_PERIOD_CHECK_IN_IN_THE_PAST)
  @JsonFormat(pattern = "dd/MM/yyyy")
  LocalDate checkIn,
  @NotNull(message = ApplicationMessage.BOOKING_PERIOD_CHECK_OUT_NOT_NULL)
  @JsonFormat(pattern = "dd/MM/yyyy")
  LocalDate checkOut,
  @Valid
  @NotEmpty(message = ApplicationMessage.HOTEL_BOOKING_ROOMS_INVALID)
  Set<@NotNull(message = ApplicationMessage.HOTEL_ROOM_NOT_NULL) BookRoomItemInput> rooms
) {

  /**
   * Validação cruzada do período. A propriedade se chama {@code checkOutAfterCheckIn} e é o
   * {@code field} que o {@code 400} lista: um campo que falta ({@code null}) é problema da restrição do
   * próprio campo, então aqui ele conta como válido para não duplicar o erro.
   */
  @JsonIgnore
  @AssertTrue(message = ApplicationMessage.BOOKING_PERIOD_CHECK_OUT_NOT_AFTER_CHECK_IN)
  public boolean isCheckOutAfterCheckIn() {
    return Optional.ofNullable(this.checkIn)
      .flatMap(in -> Optional.ofNullable(this.checkOut).map(out -> out.isAfter(in)))
      .orElse(true);
  }
}
