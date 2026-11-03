package com.hotel.booking.system.booking.service.infrastructure.db.repository;

import com.hotel.booking.system.booking.service.infrastructure.db.entity.BookingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingJpaRepository extends JpaRepository<BookingEntity, UUID> {

  /**
   * Reservas do quarto cujo período se sobrepõe ao pedido.
   *
   * <p>Dois períodos se sobrepõem quando cada um começa antes de o outro terminar. As
   * desigualdades são estritas de propósito: o check-out de uma reserva e o check-in de outra
   * no mesmo dia não conflitam. O {@code between} que havia antes só testava se uma das duas
   * pontas do período pedido caía dentro da reserva existente, e por isso não achava a reserva
   * que o período pedido envolve por inteiro.</p>
   *
   * <p>Estadia de zero noites não é pedido válido ({@code BookingPeriod.validate()}), então a
   * condição estrita não precisa tratá-la.</p>
   *
   * <p>Reserva cancelada não entra: ela não ocupa unidade nenhuma, e como o pagamento recusado
   * cancela, elas só se acumulam. A consulta roda com o lock do quarto aberto, e cada reserva
   * devolvida custa mais uma ida ao banco para carregar as linhas dela.</p>
   */
  @Query("""
      SELECT booking FROM BookingEntity booking
      JOIN booking.bookingRooms bookingRooms
      WHERE
        bookingRooms.room.id = :roomId and
        booking.status <> com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus.CANCELED and
        booking.checkIn < :checkOut and
        booking.checkOut > :checkIn
    """)
  List<BookingEntity> findByRoomIdAndPeriod(UUID roomId, LocalDate checkIn, LocalDate checkOut);

  @Query("""
    select booking from BookingEntity booking 
    where booking.reservationOrderId = :reservationOrderId
    """
  )
  Optional<BookingEntity> findByReservationOrderId(UUID reservationOrderId);
}
