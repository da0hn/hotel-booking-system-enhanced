package com.hotel.booking.system.booking.service.core.application.service;

import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.booking.service.core.domain.entity.BookingPeriod;
import com.hotel.booking.system.booking.service.core.domain.entity.BookingRoom;
import com.hotel.booking.system.booking.service.core.domain.entity.Room;
import com.hotel.booking.system.booking.service.core.ports.spi.repository.BookingRepository;
import com.hotel.booking.system.booking.service.core.ports.spi.repository.RoomRepository;
import com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus;
import com.hotel.booking.system.commons.core.domain.valueobject.HotelId;
import com.hotel.booking.system.commons.core.domain.valueobject.Money;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cenários de disponibilidade de {@link VerifyRoomAvailability}, a regra mais delicada do
 * {@code booking-service}.
 *
 * <p>O repositório é um mock que devolve exatamente o que a consulta por período devolveria;
 * a regra sob teste é o que o serviço faz com essa lista. Por isso o cenário de período
 * contido existe nas duas pontas: aqui prova-se que o <em>filtro de domínio</em> reconhece a
 * sobreposição, e a prova de que a consulta devolve essa reserva está em
 * {@code BookingJpaRepositoryIT#achaQuandoPeriodoEngloba}.</p>
 *
 * <p>A corrida entre duas reservas do último quarto não cabe num mock: ela depende do lock do
 * banco e é provada em {@code RoomAvailabilityConcurrencyIT}.</p>
 */
@DisplayName("Verificação de disponibilidade de quartos")
class VerifyRoomAvailabilityTest {

  private static final LocalDate CHECK_IN = LocalDate.of(2030, 1, 10);

  private final BookingRepository bookings = mock(BookingRepository.class);

  private final RoomRepository rooms = mock(RoomRepository.class);

  private final VerifyRoomAvailability service = new VerifyRoomAvailability(this.bookings, this.rooms);

  private final RoomId roomId = RoomId.newInstance();

  private static BookingPeriod period(final int fromDay, final int toDay) {
    return BookingPeriod.of(CHECK_IN.withDayOfMonth(fromDay), CHECK_IN.withDayOfMonth(toDay));
  }

  private static String unavailable(final RoomId roomId, final String period) {
    return "The room roomId=" + roomId + " is no longer available for period " + period;
  }

  private Room roomWithQuantity(final RoomId id, final int quantity) {
    return new Room(id, HotelId.newInstance(), 2, quantity);
  }

  private Booking booking(final RoomId id, final BookingPeriod period, final BookingStatus status, final int quantity) {
    return Booking.builder()
      .bookingPeriod(period)
      .status(status)
      .bookingRooms(List.of(BookingRoom.builder().roomId(id).quantity(quantity).price(Money.of(100)).build()))
      .build();
  }

  private void givenRoomWithQuantity(final int quantity) {
    when(this.rooms.lockAllByRoomId(any())).thenReturn(List.of(this.roomWithQuantity(this.roomId, quantity)));
  }

  private void givenExisting(final Booking... existing) {
    when(this.bookings.findBookingByRoomIdAndPeriod(any(), any())).thenReturn(List.of(existing));
  }

  @Test
  @DisplayName("aceita quando ainda sobra quarto no período")
  void aceitaQuandoSobraQuarto() {
    this.givenRoomWithQuantity(5);
    this.givenExisting(this.booking(this.roomId, period(10, 13), BookingStatus.CONFIRMED, 1));

    final var failures = this.service.execute(this.booking(this.roomId, period(10, 13), BookingStatus.PENDING, 1));

    assertThat(failures.data()).isEmpty();
  }

  @Test
  @DisplayName("aceita quando o check-in novo é o check-out de outra reserva")
  void aceitaQuandoCheckInNovoEOCheckOutDeOutraReserva() {
    this.givenRoomWithQuantity(1);
    this.givenExisting(this.booking(this.roomId, period(10, 12), BookingStatus.CONFIRMED, 1));

    final var failures = this.service.execute(this.booking(this.roomId, period(12, 14), BookingStatus.PENDING, 1));

    assertThat(failures.data()).isEmpty();
  }

  @Test
  @DisplayName("ignora reservas canceladas")
  void ignoraReservasCanceladas() {
    this.givenRoomWithQuantity(1);
    this.givenExisting(this.booking(this.roomId, period(10, 13), BookingStatus.CANCELED, 1));

    final var failures = this.service.execute(this.booking(this.roomId, period(10, 13), BookingStatus.PENDING, 1));

    assertThat(failures.data()).isEmpty();
  }

  @Test
  @DisplayName("aceita 3 + 2 unidades num quarto de 5")
  void aceitaQuandoAsUnidadesSomamExatamenteOTotal() {
    this.givenRoomWithQuantity(5);
    this.givenExisting(this.booking(this.roomId, period(10, 13), BookingStatus.CONFIRMED, 3));

    final var failures = this.service.execute(this.booking(this.roomId, period(10, 13), BookingStatus.PENDING, 2));

    assertThat(failures.data()).isEmpty();
  }

  @Test
  @DisplayName("rejeita quando o último quarto já está ocupado")
  void rejeitaQuandoUltimoQuartoEstaOcupado() {
    this.givenRoomWithQuantity(1);
    this.givenExisting(this.booking(this.roomId, period(10, 13), BookingStatus.PENDING, 1));

    final var failures = this.service.execute(this.booking(this.roomId, period(10, 13), BookingStatus.PENDING, 1));

    assertThat(failures.data()).containsExactly(unavailable(this.roomId, "10/01/2030 - 13/01/2030"));
  }

  @Test
  @DisplayName("reconhece a reserva existente inteiramente contida no período pedido")
  void reconheceReservaExistenteContidaNoPeriodoPedido() {
    this.givenRoomWithQuantity(1);
    this.givenExisting(this.booking(this.roomId, period(11, 12), BookingStatus.CONFIRMED, 1));

    final var failures = this.service.execute(this.booking(this.roomId, period(10, 13), BookingStatus.PENDING, 1));

    assertThat(failures.data()).containsExactly(unavailable(this.roomId, "10/01/2030 - 13/01/2030"));
  }

  @Test
  @DisplayName("aponta só o quarto indisponível quando o pedido tem vários quartos")
  void apontaSoOQuartoIndisponivel() {
    final var free = RoomId.newInstance();
    when(this.rooms.lockAllByRoomId(any())).thenReturn(List.of(
      this.roomWithQuantity(this.roomId, 1),
      this.roomWithQuantity(free, 1)
    ));
    when(this.bookings.findBookingByRoomIdAndPeriod(this.roomId, period(10, 13)))
      .thenReturn(List.of(this.booking(this.roomId, period(10, 13), BookingStatus.CONFIRMED, 1)));
    when(this.bookings.findBookingByRoomIdAndPeriod(free, period(10, 13))).thenReturn(List.of());
    final var requested = Booking.builder()
      .bookingPeriod(period(10, 13))
      .status(BookingStatus.PENDING)
      .bookingRooms(List.of(
        BookingRoom.builder().roomId(this.roomId).quantity(1).price(Money.of(100)).build(),
        BookingRoom.builder().roomId(free).quantity(1).price(Money.of(100)).build()
      ))
      .build();

    final var failures = this.service.execute(requested);

    assertThat(failures.data()).containsExactly(unavailable(this.roomId, "10/01/2030 - 13/01/2030"));
  }

  @Test
  @DisplayName("soma as unidades de duas linhas do mesmo quarto no pedido")
  void somaAsLinhasDoMesmoQuartoNoPedido() {
    this.givenRoomWithQuantity(5);
    this.givenExisting();
    final var duasLinhas = Booking.builder()
      .bookingPeriod(period(10, 13))
      .status(BookingStatus.PENDING)
      .bookingRooms(List.of(
        BookingRoom.builder().roomId(this.roomId).quantity(3).price(Money.of(100)).build(),
        BookingRoom.builder().roomId(this.roomId).quantity(3).price(Money.of(100)).build()
      ))
      .build();

    final var failures = this.service.execute(duasLinhas);

    assertThat(failures.data()).containsExactly(unavailable(this.roomId, "10/01/2030 - 13/01/2030"));
  }

  @Test
  @DisplayName("não quebra com linha sem quantidade: quem a recusa é o BookingInitializer")
  void naoQuebraComLinhaSemQuantidade() {
    this.givenRoomWithQuantity(1);
    this.givenExisting();
    final var semQuantidade = Booking.builder()
      .bookingPeriod(period(10, 13))
      .status(BookingStatus.PENDING)
      .bookingRooms(List.of(BookingRoom.builder().roomId(this.roomId).price(Money.of(100)).build()))
      .build();

    final var failures = this.service.execute(semQuantidade);

    assertThat(failures.data()).isEmpty();
  }

  @Test
  @DisplayName("rejeita pedido maior que o total de unidades do quarto")
  void rejeitaPedidoMaiorQueOTotalDoQuarto() {
    this.givenRoomWithQuantity(5);
    this.givenExisting();

    final var failures = this.service.execute(this.booking(this.roomId, period(10, 13), BookingStatus.PENDING, 6));

    assertThat(failures.data()).containsExactly(unavailable(this.roomId, "10/01/2030 - 13/01/2030"));
  }

  @Test
  @DisplayName("soma as unidades de cada reserva ao contar a ocupação")
  void somaAsUnidadesDeCadaReserva() {
    this.givenRoomWithQuantity(3);
    this.givenExisting(this.booking(this.roomId, period(10, 13), BookingStatus.CONFIRMED, 3));

    final var failures = this.service.execute(this.booking(this.roomId, period(10, 13), BookingStatus.PENDING, 1));

    assertThat(failures.data()).containsExactly(unavailable(this.roomId, "10/01/2030 - 13/01/2030"));
  }

  @Test
  @DisplayName("rejeita pedido que ultrapassa o que sobrou do quarto")
  void rejeitaPedidoQueUltrapassaOQueSobrou() {
    this.givenRoomWithQuantity(5);
    this.givenExisting(this.booking(this.roomId, period(10, 13), BookingStatus.CONFIRMED, 3));

    final var failures = this.service.execute(this.booking(this.roomId, period(10, 13), BookingStatus.PENDING, 3));

    assertThat(failures.data()).containsExactly(unavailable(this.roomId, "10/01/2030 - 13/01/2030"));
  }

  @Test
  @DisplayName("continua rejeitando quando o quarto já está em overbooking")
  void continuaRejeitandoQuandoJaHaOverbooking() {
    this.givenRoomWithQuantity(5);
    final var overbooked = IntStream.range(0, 6)
      .mapToObj(i -> this.booking(this.roomId, period(10, 13), BookingStatus.CONFIRMED, 1))
      .toArray(Booking[]::new);
    this.givenExisting(overbooked);

    final var failures = this.service.execute(this.booking(this.roomId, period(10, 13), BookingStatus.PENDING, 1));

    assertThat(failures.data()).containsExactly(unavailable(this.roomId, "10/01/2030 - 13/01/2030"));
  }

}
