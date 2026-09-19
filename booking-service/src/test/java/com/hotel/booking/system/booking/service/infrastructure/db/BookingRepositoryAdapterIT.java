package com.hotel.booking.system.booking.service.infrastructure.db;

import com.hotel.booking.system.booking.service.core.domain.entity.*;
import com.hotel.booking.system.booking.service.core.domain.exception.RoomNotFoundException;
import com.hotel.booking.system.booking.service.infrastructure.db.mapper.impl.BookingDatabaseMapperImpl;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters.*;
import com.hotel.booking.system.commons.core.domain.valueobject.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Import({BookingRepositoryAdapter.class, RoomRepositoryAdapter.class, BookingDatabaseMapperImpl.class})
@DisplayName("Adaptadores de repositório de reservas")
class BookingRepositoryAdapterIT extends AbstractDatabaseIT {
  private static final RoomId ROOM = RoomId.of("2223dc04-831a-4bac-aef5-e22195575cc6");
  @Autowired private BookingRepositoryAdapter bookings;
  @Autowired private RoomRepositoryAdapter rooms;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("persiste, recupera e atualiza o agregado com suas associações")
  void persisteReleEAtualizaAgregadoComPrecisaoEAssociacoes() {
    final var order = ReservationOrderId.of(UUID.randomUUID());
    final var customer = CustomerId.of(UUID.randomUUID());
    final var period = BookingPeriod.of(LocalDate.of(2035, 3, 1), LocalDate.of(2035, 3, 5));
    final var booking = Booking.builder().reservationOrderId(order).customerId(customer).bookingPeriod(period)
      .totalPrice(Money.of(new BigDecimal("20.2468")))
      .bookingRooms(List.of(BookingRoom.builder().roomId(ROOM).quantity(2).price(Money.of(new BigDecimal("10.1234"))).build())).build();
    booking.initialize();
    this.bookings.save(booking);
    this.entityManager.flush();
    this.entityManager.clear();
    final var found = this.bookings.findBookingByReservationOrderId(order).orElseThrow();
    assertThat(found.getId()).isEqualTo(booking.getId());
    assertThat(found.getCustomerId()).isEqualTo(customer);
    assertThat(found.getReservationOrderId()).isEqualTo(order);
    assertThat(found.getBookingPeriod()).isEqualTo(period);
    assertThat(found.getTotalPrice().getValue()).isEqualByComparingTo("20.2468");
    assertThat(found.getStatus()).isEqualTo(BookingStatus.PENDING);
    assertThat(found.getCreatedAt()).isNotNull();
    assertThat(found.getUpdatedAt()).isNull();
    assertThat(found.getBookingRooms()).singleElement().satisfies(item -> {
      assertThat(item.getId()).isEqualTo(booking.getBookingRooms().getFirst().getId());
      assertThat(item.getRoomId()).isEqualTo(ROOM);
      assertThat(item.getQuantity()).isEqualTo(2);
      assertThat(item.getPrice().getValue()).isEqualByComparingTo("10.1234");
    });
    assertThat(this.bookings.findBookingByRoomIdAndPeriod(ROOM, period)).extracting(Booking::getId).containsExactly(booking.getId());
    found.changeStatusTo(BookingStatus.CONFIRMED);
    this.bookings.save(found);
    this.entityManager.flush();
    this.entityManager.clear();
    assertThat(this.bookings.findBookingByReservationOrderId(order)).hasValueSatisfying(value -> {
      assertThat(value.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
      assertThat(value.getUpdatedAt()).isNotNull();
    });
  }

  @Test
  @DisplayName("mapeia quartos encontrados e traduz identificador ausente")
  void buscaQuartosMapeiaCamposERejeitaIdentificadorAusente() {
    final var entity = this.rooms.findRoomEntityById(ROOM);
    assertThat(this.rooms.findAllByRoomId(List.of(ROOM, ROOM, RoomId.of(UUID.randomUUID())))).singleElement().satisfies(room -> {
      assertThat(room.getId()).isEqualTo(ROOM);
      assertThat(room.getHotelId().getValue()).isEqualTo(entity.getHotelId());
      assertThat(room.getCapacity()).isEqualTo(entity.getCapacity());
      assertThat(room.getQuantity()).isEqualTo(entity.getQuantity());
    });
    assertThatThrownBy(() -> this.rooms.findRoomEntityById(RoomId.of(UUID.randomUUID()))).isInstanceOf(RoomNotFoundException.class);
    assertThat(this.rooms.findAllByRoomId(List.of())).isEmpty();
    assertThat(this.bookings.findBookingByReservationOrderId(ReservationOrderId.of(UUID.randomUUID()))).isEmpty();
  }
}
