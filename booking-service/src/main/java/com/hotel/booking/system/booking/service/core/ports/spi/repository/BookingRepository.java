package com.hotel.booking.system.booking.service.core.ports.spi.repository;

import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.booking.service.core.domain.entity.BookingPeriod;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository {

  List<Booking> findBookingByRoomIdAndPeriod(RoomId roomId, BookingPeriod bookingPeriod);

  /**
   * Reservas {@code PENDING} criadas antes de {@code deadline}, travadas com
   * {@code for update skip locked}. Outra instância do scheduler não vê as linhas que esta
   * transação já reservou, e não espera por elas.
   */
  List<Booking> lockPendingCreatedBefore(Instant deadline);

  void save(Booking booking);

  Optional<Booking> findBookingByReservationOrderId(ReservationOrderId reservationOrderId);
}
