package com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters;

import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.booking.service.core.domain.entity.BookingPeriod;
import com.hotel.booking.system.booking.service.core.ports.spi.repository.BookingRepository;
import com.hotel.booking.system.booking.service.infrastructure.db.mapper.BookingDatabaseMapper;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.BookingJpaRepository;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@AllArgsConstructor
public class BookingRepositoryAdapter implements BookingRepository {

  private final BookingJpaRepository bookingJpaRepository;
  private final BookingDatabaseMapper bookingDatabaseMapper;

  @Override
  public Map<RoomId, Integer> sumOccupiedUnits(final Collection<RoomId> roomIds, final BookingPeriod bookingPeriod) {
    if (roomIds.isEmpty()) {
      return Map.of();
    }
    final var rows = this.bookingJpaRepository.sumOccupiedUnits(
      roomIds.stream().map(RoomId::getValue).toList(),
      bookingPeriod.getCheckIn(),
      bookingPeriod.getCheckOut()
    );
    final var occupied = new HashMap<RoomId, Integer>();
    for (final var row : rows) {
      occupied.put(RoomId.of((UUID) row[0]), ((Number) row[1]).intValue());
    }
    return occupied;
  }

  @Override
  public List<Booking> lockPendingCreatedBefore(final Instant deadline, final int limit) {
    return this.bookingJpaRepository.lockPendingCreatedBefore(deadline, limit).stream()
      .map(this.bookingDatabaseMapper::bookingEntityToBooking)
      .toList();
  }

  @Override
  public Optional<Booking> lockByReservationOrderId(final ReservationOrderId reservationOrderId) {
    return this.bookingJpaRepository.lockByReservationOrderId(reservationOrderId.toString())
      .map(this.bookingDatabaseMapper::bookingEntityToBooking);
  }

  @Override
  public void save(final Booking booking) {
    final var bookingEntity = this.bookingDatabaseMapper.bookingToBookingEntity(booking);
    this.bookingJpaRepository.save(bookingEntity);
  }

  @Override
  public Optional<Booking> findBookingByReservationOrderId(final ReservationOrderId reservationOrderId) {
    return this.bookingJpaRepository.findByReservationOrderId(reservationOrderId.getValue())
      .map(this.bookingDatabaseMapper::bookingEntityToBooking);
  }
}
