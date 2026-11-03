package com.hotel.booking.system.booking.service.core.application.dto;

import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import com.hotel.booking.system.commons.core.domain.valueobject.FailureMessages;

/**
 * Resultado de um pedido de reserva.
 *
 * @param duplicate {@code true} quando o pedido já tinha reserva e nada foi gravado nem validado:
 *                  é uma repetição, e quem recebe a saída não deve responder de novo — a resposta
 *                  da primeira vez já foi confirmada pelo broker na mesma transação que gravou a
 *                  reserva
 */
public record BookingRoomOutput(
  Booking booking,
  CustomerReservationStatus status,
  FailureMessages failureMessages,
  boolean duplicate
) {

  public BookingRoomOutput(
    final Booking booking,
    final CustomerReservationStatus status,
    final FailureMessages failureMessages
  ) {
    this(booking, status, failureMessages, false);
  }

}
