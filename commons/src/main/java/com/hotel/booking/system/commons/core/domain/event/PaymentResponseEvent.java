package com.hotel.booking.system.commons.core.domain.event;

public abstract sealed class PaymentResponseEvent implements Event permits PaymentCompletedEvent, PaymentFailedEvent {
}
