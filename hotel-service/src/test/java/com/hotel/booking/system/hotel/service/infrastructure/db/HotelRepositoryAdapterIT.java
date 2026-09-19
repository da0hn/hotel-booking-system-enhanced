package com.hotel.booking.system.hotel.service.infrastructure.db;

import com.hotel.booking.system.commons.core.domain.valueobject.*;
import com.hotel.booking.system.hotel.service.core.application.dto.*;
import com.hotel.booking.system.hotel.service.core.application.mapper.HotelUseCaseMapperImpl;
import com.hotel.booking.system.hotel.service.core.application.usecase.BookingRoomRequestUseCaseImpl;
import com.hotel.booking.system.hotel.service.core.application.usecase.RegisterHotelUseCaseImpl;
import com.hotel.booking.system.hotel.service.core.application.usecase.SearchHotelAvailableUseCaseImpl;
import com.hotel.booking.system.hotel.service.core.domain.valueobject.*;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.*;
import com.hotel.booking.system.hotel.service.infrastructure.db.mapper.impl.HotelDatabaseMapperImpl;
import com.hotel.booking.system.hotel.service.infrastructure.db.repository.*;
import com.hotel.booking.system.hotel.service.infrastructure.db.repository.adapters.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Import({HotelRepositoryAdapter.class, LocalityRepositoryAdapter.class, HotelDatabaseMapperImpl.class})
@DisplayName("Adaptadores de repositório de hotéis")
class HotelRepositoryAdapterIT extends AbstractDatabaseIT {
  @Autowired private HotelRepositoryAdapter hotels;
  @Autowired private LocalityRepositoryAdapter localities;
  @Autowired private HotelJpaRepository jpa;
  @Autowired private HotelDatabaseMapperImpl mapper;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("persiste quartos e consulta valores de apresentação")
  void cadastroCompletoPersisteQuartosEConsultaRetornaValoresDeApresentacao() {
    final var useCaseMapper = new HotelUseCaseMapperImpl();
    final var input = RegisterHotelInput.builder().name("Hotel Integracao").description("Hospedagem de teste")
      .categoryId("bcbc43a4-5a77-44e8-9cd4-7da67b66a390").localityId("2e02993c-2b70-478e-82b2-63ff7a4991c1")
      .cep("78000-000").street("Rua da Integracao")
      .rooms(List.of(new RegisterHotelRoomInput("Suite", "Suite dupla", 2, new BigDecimal("199.9999"), 3))).build();
    final var output = new RegisterHotelUseCaseImpl(this.hotels, this.localities, useCaseMapper).execute(input);
    this.entityManager.flush();
    this.entityManager.clear();
    final var stored = this.jpa.findById(UUID.fromString(output.hotelId())).orElseThrow();
    assertThat(stored.getName()).isEqualTo(input.name());
    assertThat(stored.getDescription()).isEqualTo(input.description());
    assertThat(stored.getCategory().getId().toString()).isEqualTo(input.categoryId());
    assertThat(stored.getLocality().getId().toString()).isEqualTo(input.localityId());
    assertThat(stored.getRooms()).singleElement().satisfies(room -> {
      assertThat(room.getId().toString()).isEqualTo(output.roomsId().getFirst());
      assertThat(room.getCurrentPrice()).isEqualByComparingTo("199.9999");
      assertThat(room.getHotel().getId()).isEqualTo(stored.getId());
    });
    final var found = new SearchHotelAvailableUseCaseImpl(this.hotels, useCaseMapper).execute(
      new SearchHotelAvailableInput("cuiaba", "Mato Grosso", "Hotel", "Integracao"));
    assertThat(found).singleElement().satisfies(hotel -> {
      assertThat(hotel.id()).isEqualTo(output.hotelId());
      assertThat(hotel.name()).isEqualTo(input.name());
      assertThat(hotel.description()).isEqualTo(input.description());
      assertThat(hotel.address()).isEqualTo("Rua da Integracao - 78000-000");
      assertThat(hotel.category()).isEqualTo("Hotel");
      assertThat(hotel.city()).isEqualTo("Cuiabá");
      assertThat(hotel.state()).isEqualTo("Mato Grosso");
      assertThat(hotel.country()).isEqualTo("Brasil");
      assertThat(hotel.rooms()).singleElement().satisfies(room -> {
        assertThat(room.id()).isEqualTo(output.roomsId().getFirst());
        assertThat(room.name()).isEqualTo("Suite");
        assertThat(room.description()).isEqualTo("Suite dupla");
        assertThat(room.currentPrice()).isEqualByComparingTo("200.00");
        assertThat(room.capacity()).isEqualTo(2);
        assertThat(room.quantity()).isEqualTo(3);
      });
    });
    final var rooms = this.hotels.findAllRoomsById(output.roomsId().stream().map(RoomId::of).toList());
    assertThat(rooms).singleElement().satisfies(room -> {
      assertThat(room.getCurrentPrice().getValue()).isEqualByComparingTo("199.9999");
      assertThat(room.getHotelId().toString()).isEqualTo(output.hotelId());
      assertThat(room.getQuantity()).isEqualTo(3);
    });
    final var customer = mock(CustomerBookingRoomStatusUpdatedPublisher.class);
    final var booking = mock(BookingRoomRequestedPublisher.class);
    final var reservation = new BookingRoomRequestUseCaseImpl(this.hotels, customer, booking).execute(
      BookingRoomInput.builder().customerId("customer").hotelId(output.hotelId()).guests(2)
        .rooms(java.util.Set.of(new BookRoomItemInput(output.roomsId().getFirst(), 1))).build());
    assertThat(reservation.reservationOrderId()).isNotNull();
    verify(booking).publish(any());
    verify(customer).publish(any());
  }

  @Test
  @DisplayName("não encontra categorias, localidades e quartos ausentes")
  void idsAusentesNaoExistemENaoRetornamQuartos() {
    assertThat(this.hotels.existsCategoryById(HotelCategoryId.of(UUID.randomUUID()))).isFalse();
    assertThat(this.localities.existsLocalityById(LocalityId.of(UUID.randomUUID()))).isFalse();
    assertThat(this.hotels.findAllRoomsById(List.of(RoomId.newInstance()))).isEmpty();
    assertThat(this.hotels.searchHotelAvailableBy("hotel inexistente", "", "", "")).isEmpty();
  }

  /** Caracteriza o defeito atual: a conversão usa o id do hotel como id da localidade. */
  @Test
  @DisplayName("caracteriza a associação atual incorreta entre hotel e localidade")
  void conversaoParaDominioAtualmenteConfundeLocalidadeComHotel() {
    final var entity = this.jpa.findAll().getFirst();
    final var hotel = this.mapper.hotelEntityToHotel(entity);
    assertThat(hotel.getId().getValue()).isEqualTo(entity.getId());
    assertThat(hotel.getLocalityId().getValue()).isEqualTo(entity.getId()).isNotEqualTo(entity.getLocality().getId());
    assertThat(hotel.getCategoryId().getValue()).isEqualTo(entity.getCategory().getId());
    assertThat(hotel.getName()).isEqualTo(entity.getName());
    assertThat(hotel.getDescription()).isEqualTo(entity.getDescription());
    assertThat(hotel.getAddress().getCep()).isEqualTo(entity.getHotelCep());
    assertThat(hotel.getAddress().getStreet()).isEqualTo(entity.getHotelStreet());
    assertThat(hotel.getRoom()).hasSize(entity.getRooms().size());
  }
}
