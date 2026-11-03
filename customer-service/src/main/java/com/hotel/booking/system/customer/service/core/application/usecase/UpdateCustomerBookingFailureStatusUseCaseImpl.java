package com.hotel.booking.system.customer.service.core.application.usecase;

import com.hotel.booking.system.commons.core.application.annotation.UseCase;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.customer.service.core.application.dto.UpdateCustomerBookingFailureStatusInput;
import com.hotel.booking.system.customer.service.core.ports.api.usecase.UpdateCustomerBookingFailureStatusUseCase;
import com.hotel.booking.system.customer.service.core.ports.spi.repository.ReservationOrderRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UseCase
public class UpdateCustomerBookingFailureStatusUseCaseImpl implements UpdateCustomerBookingFailureStatusUseCase {

  private final ReservationOrderRepository repository;

  public UpdateCustomerBookingFailureStatusUseCaseImpl(final ReservationOrderRepository repository) {
    this.repository = repository;
  }

  /**
   * Naturalmente idempotente (regra 3 da issue #7): a mesma falha, com o mesmo status e os mesmos
   * motivos, não é gravada de novo.
   */
  @Override
  public void execute(final UpdateCustomerBookingFailureStatusInput input) {
    final var reservationOrder = this.repository.findById(ReservationOrderId.of(input.reservationOrderId()));
    if (!reservationOrder.updateToFailureStatus(input.status(), input.failureMessages())) {
      log.info(
        "Failure transition already recorded, ignoring | reservationOrderId={} | status={}",
        input.reservationOrderId(),
        input.status()
      );
      return;
    }
    this.repository.save(reservationOrder);
  }
}
