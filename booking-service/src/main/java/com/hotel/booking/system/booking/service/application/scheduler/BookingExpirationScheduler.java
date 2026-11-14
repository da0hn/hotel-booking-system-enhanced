package com.hotel.booking.system.booking.service.application.scheduler;

import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.booking.service.core.ports.api.usecase.ExpirePendingBookingsUseCase;
import com.hotel.booking.system.booking.service.core.ports.spi.messaging.publisher.BookingRoomResponsePublisher;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomExpiredEvent;
import com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Varre as reservas {@code PENDING} além do prazo. O cancelamento e o evento entram na mesma
 * transação: o outbox só publica se o status chegou a {@code CANCELED}. Duas instâncias não
 * expiram a mesma reserva porque a consulta trava com {@code skip locked}.
 */
@Component
public class BookingExpirationScheduler {

  public static final String EXPIRED_MESSAGE = "A reserva expirou aguardando confirmacao de pagamento";

  private final ExpirePendingBookingsUseCase expirePendingBookingsUseCase;

  private final BookingRoomResponsePublisher publisher;

  public BookingExpirationScheduler(
    final ExpirePendingBookingsUseCase expirePendingBookingsUseCase,
    final BookingRoomResponsePublisher publisher
  ) {
    this.expirePendingBookingsUseCase = expirePendingBookingsUseCase;
    this.publisher = publisher;
  }

  @Scheduled(cron = "${app.booking.expiration-scheduler.cron:0 */5 * * * *}")
  @Transactional
  public void expirePendingBookings() {
    for (final var booking : this.expirePendingBookingsUseCase.execute()) {
      this.publisher.publish(this.expiredEvent(booking));
    }
  }

  private BookingRoomExpiredEvent expiredEvent(final Booking booking) {
    return BookingRoomExpiredEvent.builder()
      .reservationOrderId(booking.getReservationOrderId().toString())
      .customerId(booking.getCustomerId().toString())
      .status(BookingStatus.CANCELED)
      .failureMessages(List.of(EXPIRED_MESSAGE))
      .build();
  }

}
