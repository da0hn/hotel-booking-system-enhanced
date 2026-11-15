package com.hotel.booking.system.booking.service.application.scheduler;

import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.booking.service.core.ports.api.usecase.ExpirePendingBookingsUseCase;
import com.hotel.booking.system.booking.service.core.ports.spi.messaging.publisher.BookingRoomResponsePublisher;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomExpiredEvent;
import com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Varre as reservas {@code PENDING} além do prazo, um lote por transação. O cancelamento e o
 * evento de cada lote entram juntos: se o outbox falha, o lote inteiro desfaz e a reserva
 * continua {@code PENDING}. O lote seguinte só começa depois do commit do anterior, então uma
 * falha não desfaz o que já foi confirmado e o lock não fica preso num lote de milhares.
 */
@Component
public class BookingExpirationScheduler {

  public static final String EXPIRED_MESSAGE = "A reserva expirou aguardando confirmacao de pagamento";

  private final ExpirePendingBookingsUseCase expirePendingBookingsUseCase;

  private final BookingRoomResponsePublisher publisher;

  private final TransactionTemplate transactions;

  private final int batchSize;

  public BookingExpirationScheduler(
    final ExpirePendingBookingsUseCase expirePendingBookingsUseCase,
    final BookingRoomResponsePublisher publisher,
    final PlatformTransactionManager transactionManager,
    @Value("${app.booking.expiration-batch-size:100}") final int batchSize
  ) {
    this.expirePendingBookingsUseCase = expirePendingBookingsUseCase;
    this.publisher = publisher;
    this.transactions = new TransactionTemplate(transactionManager);
    this.batchSize = batchSize;
  }

  @Scheduled(cron = "${app.booking.expiration-scheduler.cron:0 */5 * * * *}")
  public void expirePendingBookings() {
    while (true) {
      final var processed = this.transactions.execute(status -> {
        final var batch = this.expirePendingBookingsUseCase.execute(this.batchSize);
        for (final var booking : batch) {
          this.publisher.publish(this.expiredEvent(booking));
        }
        return batch.size();
      });
      if (processed == null || processed == 0) {
        return;
      }
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
