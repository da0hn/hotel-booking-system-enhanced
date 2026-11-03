package com.hotel.booking.system.commons.infrastructure.messaging.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Registro de eventos processados em memória")
class InMemoryProcessedEventRepositoryTest {

  private static final String TYPE = "com.hotel.booking.system.commons.core.domain.event.PaymentRequestedEvent";

  private final InMemoryProcessedEventRepository repository = new InMemoryProcessedEventRepository();

  @Test
  @DisplayName("aceita o evento na primeira vez e recusa a repetição")
  void aceitaNaPrimeiraVezERecusaARepeticao() {
    assertThat(this.repository.register("event-1", TYPE)).isTrue();
    assertThat(this.repository.register("event-1", TYPE)).isFalse();
    assertThat(this.repository.register("event-2", TYPE)).isTrue();
  }

  @Test
  @DisplayName("aceita de novo o evento liberado depois de uma falha")
  void aceitaDeNovoOEventoLiberado() {
    this.repository.register("event-1", TYPE);

    this.repository.release("event-1");

    assertThat(this.repository.register("event-1", TYPE)).isTrue();
  }

  @Test
  @DisplayName("expurga só os registros anteriores ao limite")
  void expurgaSoOsRegistrosAnterioresAoLimite() {
    final var now = Instant.parse("2026-11-02T12:00:00Z");
    final var clock = new MutableClock(now.minusSeconds(7200));
    final var repository = new InMemoryProcessedEventRepository(clock);
    repository.register("velho", TYPE);
    clock.set(now);
    repository.register("recente", TYPE);

    final var removed = repository.deleteRegisteredBefore(now.minusSeconds(3600));

    assertThat(removed).isEqualTo(1);
    assertThat(repository.register("velho", TYPE)).as("o expurgado volta a ser novo").isTrue();
    assertThat(repository.register("recente", TYPE)).as("o recente continua registrado").isFalse();
  }

}
