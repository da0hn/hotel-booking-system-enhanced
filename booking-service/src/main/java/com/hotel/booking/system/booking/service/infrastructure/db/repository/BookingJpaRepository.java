package com.hotel.booking.system.booking.service.infrastructure.db.repository;

import com.hotel.booking.system.booking.service.infrastructure.db.entity.BookingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingJpaRepository extends JpaRepository<BookingEntity, UUID> {

  /**
   * Unidades ocupadas de cada quarto, numa consulta. A sobreposição é a de
   * {@code BookingPeriod.overlaps}: {@code checkIn < checkOutPedido} e {@code checkOut > checkInPedido}.
   * Reserva cancelada não entra. Cada linha de {@code booking_room} entra uma vez na soma, inclusive
   * quando a mesma reserva tem duas linhas do mesmo quarto.
   */
  @Query("""
      select room.id, coalesce(sum(line.quantity), 0)
      from BookingRoomEntity line
      join line.booking booking
      join line.room room
      where room.id in :roomIds
        and booking.status <> com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus.CANCELED
        and booking.checkIn < :checkOut
        and booking.checkOut > :checkIn
      group by room.id
    """)
  List<Object[]> sumOccupiedUnits(Collection<UUID> roomIds, LocalDate checkIn, LocalDate checkOut);

  @Query("""
    select booking from BookingEntity booking 
    where booking.reservationOrderId = :reservationOrderId
    """
  )
  Optional<BookingEntity> findByReservationOrderId(UUID reservationOrderId);
}
