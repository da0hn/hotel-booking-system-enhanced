package com.hotel.booking.system.commons.core.domain.valueobject;

/**
 * Estado da reserva no {@code booking-service}.
 *
 * <p>{@code PENDING} espera o pagamento e pode ir para {@code CONFIRMED} ou {@code CANCELED}.
 * Os outros dois são finais: uma resposta tardia não reabre a reserva.</p>
 */
public enum BookingStatus {

  PENDING, CONFIRMED, CANCELED;

  public boolean canTransitionTo(final BookingStatus next) {
    return switch (this) {
      case PENDING -> next == CONFIRMED || next == CANCELED;
      case CONFIRMED, CANCELED -> false;
    };
  }

}
