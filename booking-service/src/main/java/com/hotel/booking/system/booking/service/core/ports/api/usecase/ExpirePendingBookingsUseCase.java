package com.hotel.booking.system.booking.service.core.ports.api.usecase;

import com.hotel.booking.system.booking.service.core.domain.entity.Booking;

import java.util.List;

public interface ExpirePendingBookingsUseCase {

  /**
   * Cancela as reservas {@code PENDING} mais velhas que o prazo e as devolve para quem publica
   * o evento de expiração na mesma transação.
   */
  List<Booking> execute();

}
