package com.hotel.booking.system.booking.service.core.domain.entity;

import com.hotel.booking.system.booking.service.core.domain.exception.*;
import com.hotel.booking.system.booking.service.core.domain.valueobject.*;
import com.hotel.booking.system.commons.core.domain.valueobject.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("Contrato do domínio de reservas")
class BookingDomainContractTest {
  private final LocalDate start = LocalDate.of(2030, 1, 10);

  @Test
  @DisplayName("valida limites, igualdade e representação do período")
  void periodoValidaNulosOrdemIgualdadeERepresentacao() {
    final var period = BookingPeriod.of(this.start, this.start.plusDays(3));
    assertThatThrownBy(() -> BookingPeriod.of(null, this.start)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> BookingPeriod.of(this.start, null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> BookingPeriod.of(this.start.plusDays(1), this.start)).isInstanceOf(BookingDomainException.class);
    assertThat(BookingPeriod.of(this.start, this.start).getCheckOut()).isEqualTo(this.start);
    assertThat(period).isEqualTo(period).isEqualTo(BookingPeriod.of(this.start, this.start.plusDays(3)))
      .isNotEqualTo(null).isNotEqualTo("period")
      .isNotEqualTo(BookingPeriod.of(this.start.plusDays(1), this.start.plusDays(3)))
      .isNotEqualTo(BookingPeriod.of(this.start, this.start.plusDays(4)));
    assertThat(period.hashCode()).isEqualTo(BookingPeriod.of(this.start, this.start.plusDays(3)).hashCode());
    assertThat(period.toString()).contains("2030-01-10", "2030-01-13");
    assertThat(period.periodAsString()).isEqualTo("10/01/2030 - 13/01/2030");
  }

  @Test
  @DisplayName("preserva UUIDs e rejeita identidades nulas")
  void identidadesPreservamUuidERejeitamNulos() {
    final var id = UUID.randomUUID();
    assertThat(BookingId.of(id.toString())).isEqualTo(BookingId.of(id));
    assertThat(BookingId.of(id).toString()).isEqualTo(id.toString());
    assertThat(BookingRoomId.of(id.toString())).isEqualTo(BookingRoomId.of(id));
    assertThat(BookingRoomId.of(id).toString()).isEqualTo(id.toString());
    assertThat(BookingId.newInstance()).isNotEqualTo(BookingId.newInstance());
    assertThat(BookingRoomId.newInstance()).isNotEqualTo(BookingRoomId.newInstance());
    assertThatThrownBy(() -> BookingId.of((String) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> BookingId.of((UUID) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> BookingRoomId.of((String) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> BookingRoomId.of((UUID) null)).isInstanceOf(NullPointerException.class);
  }

  private Booking booking(final ReservationOrderId order, final CustomerId customer, final BookingStatus status) {
    return new Booking(BookingId.newInstance(), order, customer, BookingPeriod.of(this.start, this.start.plusDays(3)),
      Money.ZERO, null, 1, status);
  }

  @Test
  @DisplayName("rejeita identificadores e status obrigatórios ausentes")
  void reservaRejeitaIdentificadoresEStatusAusentes() {
    final var order = ReservationOrderId.newInstance();
    final var customer = CustomerId.newInstance();
    assertThatThrownBy(() -> this.booking(null, customer, BookingStatus.PENDING).validate()).isInstanceOf(BookingDomainException.class);
    assertThatThrownBy(() -> this.booking(ReservationOrderId.of((UUID) null), customer, BookingStatus.PENDING).validate()).isInstanceOf(BookingDomainException.class);
    assertThatThrownBy(() -> this.booking(order, null, BookingStatus.PENDING).validate()).isInstanceOf(BookingDomainException.class);
    assertThatThrownBy(() -> this.booking(order, new EmptyCustomerId(), BookingStatus.PENDING).validate()).isInstanceOf(BookingDomainException.class);
    assertThatThrownBy(() -> this.booking(order, customer, null).validate()).isInstanceOf(BookingDomainException.class);
    final var valid = this.booking(order, customer, BookingStatus.PENDING);
    assertThatCode(valid::validate).doesNotThrowAnyException();
    assertThat(valid.getBookingRooms()).isEmpty();
    assertThatThrownBy(() -> valid.changeStatusTo(null)).isInstanceOf(NullPointerException.class);
    assertThat(valid.getStatus()).isEqualTo(BookingStatus.PENDING);
  }

  @Test
  @DisplayName("copia os quartos e calcula o total de cada item")
  void construtorCopiaListaEQuartoCalculaQuantidade() {
    final var room = new BookingRoom(BookingRoomId.newInstance(), RoomId.newInstance(), BookingId.newInstance(), 3,
      Money.of(new BigDecimal("2.1234")));
    final var items = new ArrayList<>(List.of(room));
    final var booking = new Booking(BookingId.newInstance(), ReservationOrderId.newInstance(), CustomerId.newInstance(),
      BookingPeriod.of(this.start, this.start), Money.of(new BigDecimal("6.3702")), items, 1, BookingStatus.PENDING);
    items.clear();
    assertThat(booking.getBookingRooms()).containsExactly(room);
    assertThat(room.getTotalPrice().getValue()).isEqualByComparingTo("6.3702");
    assertThatCode(booking::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("preserva mensagem e causa nas exceções de domínio")
  void excecoesPreservamMensagemECausa() {
    final var cause = new IllegalStateException("root");
    assertThat(new BookingDomainException().getMessage()).isNull();
    assertThat(new RoomNotFoundException().getMessage()).isNull();
    assertThat(new BookingDomainException("failure", cause)).hasMessage("failure").hasCause(cause);
    assertThat(new RoomNotFoundException("missing", cause)).hasMessage("missing").hasCause(cause);
  }

  private static final class EmptyCustomerId extends CustomerId {
    private EmptyCustomerId() {
      super(null);
    }
  }
}
