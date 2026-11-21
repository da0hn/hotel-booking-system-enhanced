package com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters;

import com.hotel.booking.system.booking.service.infrastructure.db.mapper.BookingDatabaseMapper;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.RoomJpaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * O PostgreSQL entende {@code lock_timeout = 0} como "sem limite". Um {@code 0s} (ou uma duração
 * que arredonda para 0 ms) em {@code app.booking.room-lock-timeout} devolveria a espera ilimitada
 * que o limite existe para evitar, então o adaptador recusa o valor ao ser construído.
 */
@DisplayName("Limite de espera do lock no adaptador de quartos")
class RoomRepositoryAdapterTest {

  @ParameterizedTest(name = "{0}")
  @DisplayName("recusa duração que desligaria o lock_timeout")
  @ValueSource(strings = {"PT0S", "PT-1S", "PT0.0009S"})
  void recusaDuracaoQueDesligariaOLimite(final String duracao) {
    assertThatThrownBy(() -> this.adapter(Duration.parse(duracao)))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("app.booking.room-lock-timeout");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("aceita a menor duração que o PostgreSQL entende e as de segundos")
  @ValueSource(strings = {"PT0.001S", "PT0.3S", "PT10S"})
  void aceitaDuracaoDeUmMilissegundoOuMais(final String duracao) {
    assertThatCode(() -> this.adapter(Duration.parse(duracao))).doesNotThrowAnyException();
  }

  private RoomRepositoryAdapter adapter(final Duration limite) {
    return new RoomRepositoryAdapter(
      mock(RoomJpaRepository.class),
      mock(BookingDatabaseMapper.class),
      mock(EntityManager.class),
      limite
    );
  }

}
