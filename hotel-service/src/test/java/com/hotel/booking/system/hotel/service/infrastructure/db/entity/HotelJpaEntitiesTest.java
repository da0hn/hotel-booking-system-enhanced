package com.hotel.booking.system.hotel.service.infrastructure.db.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Entidades JPA de hotéis")
class HotelJpaEntitiesTest {

  @Test
  @DisplayName("preserva identidade e relações das entidades persistidas")
  void preservesIdentityAndRelationshipsOfPersistedEntities() {
    final var localityId = UUID.randomUUID();
    final var categoryId = UUID.randomUUID();
    final var hotelId = UUID.randomUUID();
    final var roomId = UUID.randomUUID();
    final var locality = LocalityEntity.builder().id(localityId).city("Cuiabá").state("MT").country("Brasil").build();
    final var category = HotelCategoryEntity.builder().id(categoryId).name("Pousada").hotels(Set.of()).build();
    final var hotel = HotelEntity.builder().id(hotelId).name("Portal").description("Desc")
      .hotelCep("78000-000").hotelStreet("Rua A").locality(locality).category(category).rooms(Set.of()).build();
    final var room = RoomEntity.builder().id(roomId).name("Suíte").description("Vista")
      .capacity(2).currentPrice(new BigDecimal("100.0000")).hotel(hotel).quantity(3).build();

    assertThat(locality).isEqualTo(locality).isNotEqualTo(null).isNotEqualTo(category);
    assertThat(LocalityEntity.builder().id(localityId).build()).isEqualTo(locality);
    assertThat(LocalityEntity.builder().id(UUID.randomUUID()).build()).isNotEqualTo(locality);
    assertThat(locality.hashCode()).isEqualTo(localityId.hashCode());
    assertThat(category).isEqualTo(category).isNotEqualTo(null).isNotEqualTo(hotel);
    assertThat(HotelCategoryEntity.builder().id(categoryId).name("Pousada").hotels(Set.of()).build()).isEqualTo(category);
    assertThat(HotelCategoryEntity.builder().id(UUID.randomUUID()).name("Pousada").hotels(Set.of())).isNotEqualTo(category);
    assertThat(HotelCategoryEntity.builder().id(categoryId).name("Outro").hotels(Set.of())).isNotEqualTo(category);
    assertThat(category.hashCode()).isEqualTo(HotelCategoryEntity.builder().id(categoryId).name("Pousada").hotels(Set.of()).build().hashCode());
    assertThat(hotel).isEqualTo(HotelEntity.builder().id(hotelId).build()).isNotEqualTo(HotelEntity.builder().id(UUID.randomUUID()).build());
    assertThat(hotel.hashCode()).isEqualTo(hotelId.hashCode());
    assertThat(room).isEqualTo(RoomEntity.builder().id(roomId).build()).isNotEqualTo(RoomEntity.builder().id(UUID.randomUUID()).build());
    assertThat(room.hashCode()).isEqualTo(roomId.hashCode());
    assertThat(hotel.getLocality()).isSameAs(locality);
    assertThat(hotel.getCategory()).isSameAs(category);
    assertThat(room.getHotel()).isSameAs(hotel);
  }
}
