package com.hotel.booking.system.booking.service.core.application.usecase;

import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.booking.service.core.ports.api.usecase.ExpirePendingBookingsUseCase;
import com.hotel.booking.system.booking.service.core.ports.spi.repository.BookingRepository;
import com.hotel.booking.system.commons.core.application.annotation.UseCase;
import com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Cancela a reserva que ficou {@code PENDING} além de {@code app.booking.pending-timeout}.
 * O quarto volta a contar como livre porque a ocupação ignora {@code CANCELED}. Quem chamou
 * publica o {@code BookingRoomExpiredEvent} na mesma transação.
 */
@UseCase
public class ExpirePendingBookingsUseCaseImpl implements ExpirePendingBookingsUseCase {

  private final BookingRepository bookingRepository;

  private final Duration pendingTimeout;

  public ExpirePendingBookingsUseCaseImpl(
    final BookingRepository bookingRepository,
    @Value("${app.booking.pending-timeout}") final Duration pendingTimeout
  ) {
    this.bookingRepository = bookingRepository;
    this.pendingTimeout = pendingTimeout;
  }

  @Override
  public List<Booking> execute(final int limit) {
    final var deadline = Instant.now().minus(this.pendingTimeout);
    final var expired = this.bookingRepository.lockPendingCreatedBefore(deadline, limit);
    for (final var booking : expired) {
      booking.changeStatusTo(BookingStatus.CANCELED);
      this.bookingRepository.save(booking);
    }
    return List.copyOf(expired);
  }

}
