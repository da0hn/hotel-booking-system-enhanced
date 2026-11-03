package com.hotel.booking.system.booking.service.infrastructure.db.repository;

import com.hotel.booking.system.booking.service.infrastructure.db.entity.RoomEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface RoomJpaRepository extends JpaRepository<RoomEntity, UUID> {

  /**
   * Carrega os quartos e os trava para escrita ({@code SELECT ... FOR UPDATE}) até o fim da
   * transação corrente.
   *
   * <p>O {@code order by} não é enfeite: ele fixa a ordem em que os locks são tomados. Dois
   * pedidos que envolvam os quartos A e B, um na ordem {@code A, B} e o outro em {@code B, A},
   * esperariam um pelo outro para sempre se cada um travasse na ordem do próprio pedido.</p>
   *
   * <p>Exige transação aberta: fora dela o JPA lança {@code TransactionRequiredException} em
   * vez de travar em silêncio.</p>
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select room from RoomEntity room where room.id in :ids order by room.id")
  List<RoomEntity> findAllByIdForUpdate(Collection<UUID> ids);

}
