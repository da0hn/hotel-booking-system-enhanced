package com.hotel.booking.system.customer.service.infrastructure.db.repository.adapters;

import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.customer.service.core.domain.entity.ReservationOrder;
import com.hotel.booking.system.customer.service.core.domain.exception.ReservationOrderNotFoundException;
import com.hotel.booking.system.customer.service.core.ports.spi.repository.ReservationOrderRepository;
import com.hotel.booking.system.customer.service.infrastructure.db.mapper.CustomerDatabaseMapper;
import com.hotel.booking.system.customer.service.infrastructure.db.repository.ReservationOrderJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class ReservationOrderRepositoryAdapter implements ReservationOrderRepository {

  private final CustomerRepositoryAdapter customerRepositoryAdapter;
  private final ReservationOrderJpaRepository repository;
  private final CustomerDatabaseMapper mapper;
  private final EntityManager entityManager;


  /**
   * Grava o pedido. Se ele já existe, força o incremento da {@code version} mesmo quando nenhuma
   * coluna de {@code reservation_order} muda: a transição que só acrescenta uma entrada à
   * timeline (o mesmo status com outro motivo) altera apenas {@code history}, a coleção inversa,
   * e o Hibernate não incrementa a versão do pai por mudança nela. Sem o incremento essa
   * transição passaria por cima de uma mudança de status de outra transação, e o
   * {@code current_status} ficaria atrás da última entrada da timeline. Com ele, a que
   * commita por último falha em toda transição concorrente, e o retry relê o pedido commitado.
   */
  @Override
  public void save(final ReservationOrder reservationOrder) {
    final var entity = this.mapper.reservationOrderToReservationOrderEntity(
      reservationOrder,
      this.customerRepositoryAdapter.findCustomerEntityById(reservationOrder.getCustomerId())
    );
    final var saved = this.repository.save(entity);
    if (reservationOrder.getVersion().isPresent()) {
      this.entityManager.lock(saved, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    }
  }

  @Override
  public ReservationOrder findById(final ReservationOrderId reservationOrderId) {
    return this.repository.findById(reservationOrderId.getValue())
      .map(this.mapper::reservationOrderEntityToReservationOrder)
      .orElseThrow(ReservationOrderNotFoundException::new);
  }

  @Override
  public boolean exists(final ReservationOrderId reservationOrderId) {
    return this.repository.existsById(reservationOrderId.getValue());
  }
}
