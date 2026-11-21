package com.hotel.booking.system.booking.service.infrastructure.db.repository;

import com.hotel.booking.system.booking.service.infrastructure.db.entity.BookingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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

  /**
   * As reservas {@code PENDING} mais velhas que o prazo, na ordem em que foram criadas, já
   * travadas. {@code skip locked} deixa a outra instância do scheduler seguir com as linhas que
   * esta transação ainda não soltou, em vez de esperar e expirar a mesma reserva duas vezes.
   *
   * <p>A consulta devolve a entidade, e não só o id: buscar cada reserva por {@code findById}
   * depois do lock custava uma ida ao banco por reserva, com o lock do lote aberto. O lock é só
   * de {@code booking}; as linhas de {@code booking_room} vêm em lote ({@code @BatchSize}).</p>
   */
  @Query(value = """
    select * from booking
    where status = 'PENDING' and created_at < :deadline
    order by created_at
    limit :limit
    for update skip locked
    """, nativeQuery = true)
  List<BookingEntity> lockPendingCreatedBefore(Instant deadline, int limit);

  /**
   * A reserva do pedido, travada. O pagamento e a expiração leem e gravam o status da mesma
   * reserva, e o lock faz uma esperar a outra decidir sobre o estado que a primeira gravou.
   */
  @Query(value = """
    select * from booking
    where reservation_order_id = :reservationOrderId
    for update
    """, nativeQuery = true)
  Optional<BookingEntity> lockByReservationOrderId(String reservationOrderId);
}
