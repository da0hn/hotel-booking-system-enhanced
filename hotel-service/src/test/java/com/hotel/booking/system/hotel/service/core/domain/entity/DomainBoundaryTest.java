package com.hotel.booking.system.hotel.service.core.domain.entity;

import com.hotel.booking.system.commons.core.domain.valueobject.*;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import com.hotel.booking.system.hotel.service.core.domain.valueobject.*;
import com.hotel.booking.system.hotel.service.core.domain.exception.HotelDomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@DisplayName("Limites do domínio de hotéis")
class DomainBoundaryTest {
  private final HotelId hotelId = HotelId.newInstance();
  private final HotelCategoryId categoryId = HotelCategoryId.of(UUID.randomUUID());
  private final LocalityId localityId = LocalityId.of(UUID.randomUUID());

  @ParameterizedTest
  @DisplayName("rejeita CEP ausente ou fora do formato")
  @NullAndEmptySource
  @ValueSource(strings = {" ", "78000000", "12345-6789"})
  void rejeitaCepAusenteOuForaDoFormato(final String cep) {
    assertThatThrownBy(() -> new HotelAddress(cep, "Rua A").validate()).hasMessage(ApplicationMessage.HOTEL_CEP_INVALID);
  }

  @Test
  @DisplayName("rejeita relacionamentos e quantidade obrigatórios ausentes")
  void rejeitaRelacionamentosAusentesEQuantidadeAusenteOuNaoPositiva() {
    assertThatThrownBy(() -> this.room(null, 1).validate()).hasMessage(ApplicationMessage.HOTEL_ROOM_RELATIONSHIP_NOT_FOUND);
    assertThatThrownBy(() -> this.room(this.hotelId, null).validate()).hasMessage(ApplicationMessage.HOTEL_ROOM_QUANTITY_NOT_NULL);
    assertThatThrownBy(() -> this.room(this.hotelId, 0).validate()).hasMessage(ApplicationMessage.HOTEL_ROOM_QUANTITY_INVALID);
    assertThatThrownBy(() -> this.room(this.hotelId, -1).validate()).hasMessage(ApplicationMessage.HOTEL_ROOM_QUANTITY_INVALID);
    assertThatThrownBy(() -> this.hotel(null, this.localityId).validate()).hasMessage(ApplicationMessage.HOTEL_CATEGORY_NOT_NULL);
    assertThatThrownBy(() -> this.hotel(this.categoryId, null).validate()).hasMessage(ApplicationMessage.HOTEL_LOCALITY_NOT_NULL);
  }

  @Test
  @DisplayName("trata capacidade e quantidade nos limites inclusivos")
  void limitesDeCapacidadeEQuantidadeSaoInclusivos() {
    final var room = this.room(this.hotelId, 3);
    assertThat(room.hasCapacityFor(2)).isTrue();
    assertThat(room.hasCapacityFor(3)).isFalse();
    assertThat(room.hasQuantityAvailable(3)).isTrue();
    assertThat(room.hasQuantityAvailable(4)).isFalse();
    assertThat(room.getHotelId()).isEqualTo(this.hotelId);
    assertThat(room.getQuantity()).isEqualTo(3);
  }

  @Test
  @DisplayName("preserva UUIDs e representação textual das identidades")
  void identidadesAceitamUuidEPreservamRepresentacaoTextual() {
    final var id = UUID.randomUUID();
    assertThat(LocalityId.of(id).toString()).isEqualTo(id.toString());
    assertThat(HotelCategoryId.of(id).toString()).isEqualTo(id.toString());
    assertThat(new Locality(LocalityId.of(id), "Cuiaba", "MT", "Brasil").getId()).isEqualTo(LocalityId.of(id));
    assertThat(new HotelCategory(HotelCategoryId.of(id), "Hotel", "Hospedagem").getId()).isEqualTo(HotelCategoryId.of(id));
    final var cause = new IllegalStateException("original");
    assertThat(new HotelDomainException("contexto", cause)).hasMessage("contexto").hasCause(cause);
  }

  private Room room(final HotelId hotel, final Integer quantity) {
    return new Room(RoomId.newInstance(), hotel, "Suite", "Suite dupla", 2, Money.of(100), quantity);
  }

  private Hotel hotel(final HotelCategoryId category, final LocalityId locality) {
    return new Hotel(this.hotelId, "Hotel", "Descricao", category, new HotelAddress("78000-000", "Rua A"), locality,
      Rooms.of(this.room(this.hotelId, 1)));
  }
}
