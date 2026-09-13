package com.hotel.booking.system.commons.core.domain;

import com.hotel.booking.system.commons.core.domain.valueobject.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Identidade de domínio")
class DomainIdentityTest {

  @Test
  @DisplayName("preserva o UUID nas fábricas e gera uma nova identidade")
  void fabricasPreservamUuidEGeramIdentidadesNovas() {
    this.verificaFabricas(HotelId::of, HotelId::of, HotelId::newInstance);
    this.verificaFabricas(RoomId::of, RoomId::of, RoomId::newInstance);
    this.verificaFabricas(CustomerId::of, CustomerId::of, CustomerId::newInstance);
    this.verificaFabricas(PaymentId::of, PaymentId::of, PaymentId::newInstance);
    this.verificaFabricas(ReservationOrderId::of, ReservationOrderId::of, ReservationOrderId::newInstance);
  }

  private <T extends AbstractDomainEntityId<UUID>> void verificaFabricas(
    final Function<UUID, T> uuidFactory, final Function<String, T> stringFactory,
    final Supplier<T> generator
  ) {
    final var uuid = UUID.randomUUID();
    final var id = uuidFactory.apply(uuid);
    assertThat(id.getValue()).isEqualTo(uuid);
    assertThat(id.empty()).isFalse();
    assertThat(id.toString()).isEqualTo(uuid.toString());
    assertThat(id).isEqualTo(stringFactory.apply(uuid.toString()));
    assertThat(id.hashCode()).isEqualTo(stringFactory.apply(uuid.toString()).hashCode());
    assertThat(id.equals(id)).isTrue();
    assertThat(id.equals(null)).isFalse();
    assertThat(id.equals(uuid)).isFalse();
    assertThat(id).isNotEqualTo(uuidFactory.apply(UUID.randomUUID()));
    assertThat(generator.get().getValue()).isNotNull().isNotEqualTo(generator.get().getValue());
    assertThatThrownBy(() -> stringFactory.apply("uuid-invalido")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("não confunde IDs de tipos de domínio diferentes")
  void idsDeDominiosDiferentesNaoSeConfundemMesmoComMesmoValor() {
    final var uuid = UUID.randomUUID();
    assertThat(HotelId.of(uuid)).isNotEqualTo(RoomId.of(uuid));
    assertThat(new Id(uuid).toString()).isEqualTo("AbstractDomainEntityId[value=" + uuid + "]");
    assertThat(new Id(null).empty()).isTrue();
    assertThat(PaymentId.of((UUID) null).empty()).isTrue();
    assertThat(ReservationOrderId.of((UUID) null).empty()).isTrue();
  }

  @Test
  @DisplayName("rejeita valores nulos nos IDs que exigem referência")
  void hotelQuartoEClienteRejeitamIdNulo() {
    assertThatNullPointerException().isThrownBy(() -> HotelId.of((UUID) null));
    assertThatNullPointerException().isThrownBy(() -> HotelId.of((String) null));
    assertThatNullPointerException().isThrownBy(() -> RoomId.of((UUID) null));
    assertThatNullPointerException().isThrownBy(() -> RoomId.of((String) null));
    assertThatNullPointerException().isThrownBy(() -> CustomerId.of((UUID) null));
    assertThatNullPointerException().isThrownBy(() -> CustomerId.of((String) null));
  }

  @Test
  @DisplayName("compara entidades pelo tipo e pela identidade e permite inicializá-la")
  void entidadeComparaTipoEIdentidadeEPermiteInicializarId() {
    final var id = new Id(UUID.randomUUID());
    final var entity = new Entity(id);
    final var equivalent = new Entity(new Id(id.getValue()));
    assertThat(entity.equals(entity)).isTrue();
    assertThat(entity.equals(null)).isFalse();
    assertThat(entity.equals(id)).isFalse();
    assertThat(entity).isEqualTo(equivalent);
    assertThat(entity.hashCode()).isEqualTo(equivalent.hashCode());
    assertThat(entity.toString()).isEqualTo("AbstractDomainEntity[id=" + id + "]");
    final var other = new Id(UUID.randomUUID());
    entity.setId(other);
    assertThat(entity.getId()).isSameAs(other);
    assertThat(entity).isNotEqualTo(equivalent);
  }

  private static final class Id extends AbstractDomainEntityId<UUID> {
    private Id(final UUID value) { super(value); }
  }

  private static final class Entity extends AbstractDomainEntity<Id> {
    private Entity(final Id id) { super(id); }
  }
}
