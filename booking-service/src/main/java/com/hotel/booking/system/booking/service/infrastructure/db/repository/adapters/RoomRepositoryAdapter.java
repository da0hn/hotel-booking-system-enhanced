package com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters;

import com.hotel.booking.system.booking.service.core.domain.entity.Room;
import com.hotel.booking.system.booking.service.core.domain.exception.RoomNotFoundException;
import com.hotel.booking.system.booking.service.core.ports.spi.repository.RoomRepository;
import com.hotel.booking.system.booking.service.infrastructure.db.entity.RoomEntity;
import com.hotel.booking.system.booking.service.infrastructure.db.mapper.BookingDatabaseMapper;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.RoomJpaRepository;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class RoomRepositoryAdapter implements RoomRepository {

  private final RoomJpaRepository roomJpaRepository;
  private final BookingDatabaseMapper bookingDatabaseMapper;
  private final EntityManager entityManager;
  private final Duration roomLockTimeout;

  public RoomRepositoryAdapter(
    final RoomJpaRepository roomJpaRepository,
    @Lazy final BookingDatabaseMapper bookingDatabaseMapper,
    final EntityManager entityManager,
    @Value("${app.booking.room-lock-timeout:10s}") final Duration roomLockTimeout
  ) {
    this.roomJpaRepository = roomJpaRepository;
    this.bookingDatabaseMapper = bookingDatabaseMapper;
    this.entityManager = entityManager;
    this.roomLockTimeout = roomLockTimeout;
  }

  public RoomEntity findRoomEntityById(final RoomId roomId) {
    return this.roomJpaRepository.findById(roomId.getValue())
      .orElseThrow(() -> new RoomNotFoundException(ApplicationMessage.HOTEL_ROOM_NOT_FOUND));
  }

  /**
   * {@code lock_timeout} vale só nesta transação ({@code set_config} local). Estourar o prazo
   * falha a sentença; quem chamou desfaz a transação e não grava a reserva. Não é recusa de
   * negócio, então não vira evento de falha: a exceção segue o retry do listener.
   */
  @Override
  public List<Room> lockAllByRoomId(final Collection<? extends RoomId> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    this.entityManager.createNativeQuery("select set_config('lock_timeout', ?1, true)")
      .setParameter(1, this.postgresTimeout(this.roomLockTimeout))
      .getSingleResult();
    return this.roomJpaRepository.findAllByIdForUpdate(ids.stream()
        .map(RoomId::getValue)
        .collect(Collectors.toSet())
      ).stream()
      .map(this.bookingDatabaseMapper::roomEntityToRoom)
      .collect(Collectors.toList());
  }

  private String postgresTimeout(final Duration duration) {
    final var millis = duration.toMillis();
    if (millis % 1000 == 0) {
      return (millis / 1000) + "s";
    }
    return millis + "ms";
  }
}
