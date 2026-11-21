package com.hotel.booking.system.booking.service.core.ports.spi.repository;

import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.booking.service.core.domain.entity.BookingPeriod;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface BookingRepository {

  /**
   * Soma das unidades ocupadas de cada quarto, numa consulta só. Quarto sem linha não entra no
   * mapa e vale zero. A sobreposição é a de {@code BookingPeriod.overlaps}: check-out no dia do
   * check-in seguinte não conta.
   */
  Map<RoomId, Integer> sumOccupiedUnits(Collection<RoomId> roomIds, BookingPeriod bookingPeriod);

  void save(Booking booking);

  Optional<Booking> findBookingByReservationOrderId(ReservationOrderId reservationOrderId);
}
