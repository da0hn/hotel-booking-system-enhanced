package com.hotel.booking.system.commons.infrastructure.messaging.idempotency;

import com.hotel.booking.system.commons.core.application.idempotency.ProcessedEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("Expurgo dos eventos processados")
class ProcessedEventPurgeJobTest {

  @Test
  @DisplayName("remove os registros mais antigos que o período de retenção")
  void removeOsRegistrosMaisAntigosQueARetencao() {
    final var repository = mock(ProcessedEventRepository.class);
    final var clock = new MutableClock(Instant.parse("2026-11-09T03:00:00Z"));
    final var job = new ProcessedEventPurgeJob(repository, Duration.ofDays(7), clock);

    job.purge();

    verify(repository).deleteRegisteredBefore(Instant.parse("2026-11-02T03:00:00Z"));
  }

}
