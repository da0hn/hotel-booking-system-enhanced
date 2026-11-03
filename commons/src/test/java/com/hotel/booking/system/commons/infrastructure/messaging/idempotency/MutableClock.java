package com.hotel.booking.system.commons.infrastructure.messaging.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Relógio de teste cujo instante se escolhe: o TTL do registro é uma conta sobre o tempo, e
 * esperá-lo de verdade tornaria o teste lento e instável.
 */
final class MutableClock extends Clock {

  private Instant now;

  MutableClock(final Instant now) {
    this.now = now;
  }

  void set(final Instant now) {
    this.now = now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(final ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return this.now;
  }

}
