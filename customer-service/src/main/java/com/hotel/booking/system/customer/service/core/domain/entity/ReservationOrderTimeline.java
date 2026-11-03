package com.hotel.booking.system.customer.service.core.domain.entity;

import com.hotel.booking.system.commons.core.domain.AbstractDomainEntity;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import com.hotel.booking.system.commons.core.domain.valueobject.FailureMessages;
import com.hotel.booking.system.customer.service.core.domain.valueobject.ReservationOrderTimelineId;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Predicate;

@Getter
@SuperBuilder
public class ReservationOrderTimeline extends AbstractDomainEntity<ReservationOrderTimelineId> {

  private CustomerReservationStatus status;
  private String reason;
  private Instant occurredAt;

  public ReservationOrderTimeline(
    final ReservationOrderTimelineId id,
    final CustomerReservationStatus status,
    final String reason,
    final Instant occurredAt
  ) {
    super(id);
    this.status = status;
    this.reason = reason;
    this.occurredAt = occurredAt;
  }

  /**
   * Duas entradas descrevem a mesma transição quando têm o mesmo status e o mesmo motivo. O id e o
   * instante ficam fora da comparação: são justamente o que muda a cada entrega do mesmo evento.
   * Motivo nulo e em branco são a mesma ausência, porque a coluna devolve um ou outro conforme
   * quem gravou.
   */
  public boolean isSameTransitionAs(final ReservationOrderTimeline other) {
    return this.status == other.status && this.normalizedReason().equals(other.normalizedReason());
  }

  private Optional<String> normalizedReason() {
    return Optional.ofNullable(this.reason).filter(Predicate.not(String::isBlank));
  }

  public static ReservationOrderTimeline update(final CustomerReservationStatus status) {
    return ReservationOrderTimeline.builder()
      .id(ReservationOrderTimelineId.newInstance())
      .status(status)
      .occurredAt(Instant.now())
      .build();
  }

  public static ReservationOrderTimeline update(final CustomerReservationStatus status, final FailureMessages failureMessages) {
    final var failureReason = String.join("\n", failureMessages);
    return ReservationOrderTimeline.builder()
      .id(ReservationOrderTimelineId.newInstance())
      .status(status)
      .reason(failureReason)
      .occurredAt(Instant.now())
      .build();
  }

}
