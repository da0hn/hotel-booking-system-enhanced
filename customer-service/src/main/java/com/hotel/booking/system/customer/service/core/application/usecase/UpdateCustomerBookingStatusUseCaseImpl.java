package com.hotel.booking.system.customer.service.core.application.usecase;

import com.hotel.booking.system.commons.core.application.annotation.UseCase;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.customer.service.core.application.dto.UpdateCustomerBookingStatusInput;
import com.hotel.booking.system.customer.service.core.ports.api.usecase.UpdateCustomerBookingStatusUseCase;
import com.hotel.booking.system.customer.service.core.ports.spi.repository.ReservationOrderRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UseCase
public class UpdateCustomerBookingStatusUseCaseImpl implements UpdateCustomerBookingStatusUseCase {

  private final ReservationOrderRepository repository;

  public UpdateCustomerBookingStatusUseCaseImpl(final ReservationOrderRepository repository) {
    this.repository = repository;
  }

  /**
   * Naturalmente idempotente (regra 3 da issue #7): se o pedido já registrou essa transição como a
   * mais recente, não grava nada. Vale mesmo para um evento com {@code eventId} novo, que a
   * deduplicação por evento não reconheceria.
   */
  @Override
  public void execute(final UpdateCustomerBookingStatusInput input) {
    final var reservationOrder = this.repository.findById(ReservationOrderId.of(input.reservationOrderId()));
    if (!reservationOrder.updateStatus(input.status())) {
      log.info(
        "Status transition already recorded, ignoring | reservationOrderId={} | status={}",
        input.reservationOrderId(),
        input.status()
      );
      return;
    }
    this.repository.save(reservationOrder);
  }
}
