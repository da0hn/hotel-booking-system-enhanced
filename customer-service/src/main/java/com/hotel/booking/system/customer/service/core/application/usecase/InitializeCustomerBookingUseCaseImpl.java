package com.hotel.booking.system.customer.service.core.application.usecase;

import com.hotel.booking.system.commons.core.application.annotation.UseCase;
import com.hotel.booking.system.customer.service.core.application.dto.InitializeReservationOrderInput;
import com.hotel.booking.system.customer.service.core.domain.entity.ReservationOrder;
import com.hotel.booking.system.customer.service.core.domain.exception.CustomerNotFoundException;
import com.hotel.booking.system.customer.service.core.ports.api.mapper.CustomerUseCaseMapper;
import com.hotel.booking.system.customer.service.core.ports.api.usecase.InitializeCustomerBookingUseCase;
import com.hotel.booking.system.customer.service.core.ports.spi.repository.CustomerRepository;
import com.hotel.booking.system.customer.service.core.ports.spi.repository.ReservationOrderRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UseCase
public class InitializeCustomerBookingUseCaseImpl implements InitializeCustomerBookingUseCase {

  private final CustomerRepository customerRepository;
  private final ReservationOrderRepository reservationOrderRepository;
  private final CustomerUseCaseMapper customerUseCaseMapper;

  public InitializeCustomerBookingUseCaseImpl(
    final CustomerRepository customerRepository,
    final ReservationOrderRepository reservationOrderRepository,
    final CustomerUseCaseMapper customerUseCaseMapper
  ) {
    this.customerRepository = customerRepository;
    this.reservationOrderRepository = reservationOrderRepository;
    this.customerUseCaseMapper = customerUseCaseMapper;
  }

  /**
   * Naturalmente idempotente (regra 3 da issue #7): o pedido nasce uma vez. Gravar de novo o
   * agregado recém-montado com o mesmo id reescreveria o {@code current_status} de volta para
   * {@code AWAITING_RESERVATION} e substituiria a timeline pela de uma entrada só, desfazendo o
   * andamento da saga.
   */
  @Override
  public void execute(final InitializeReservationOrderInput input) {
    final var reservationOrder = this.customerUseCaseMapper.initializeReservationOrderInputToReservationOrder(input);
    this.ensureCustomerExists(reservationOrder);
    if (this.reservationOrderRepository.exists(reservationOrder.getId())) {
      log.info("Reservation order already initialized, ignoring | reservationOrderId={}", reservationOrder.getId());
      return;
    }
    reservationOrder.initialize();
    this.reservationOrderRepository.save(reservationOrder);
  }

  private void ensureCustomerExists(final ReservationOrder reservationOrder) {
    if (!this.customerRepository.customerExistsBy(reservationOrder.getCustomerId())) {
      throw new CustomerNotFoundException();
    }
  }

}
