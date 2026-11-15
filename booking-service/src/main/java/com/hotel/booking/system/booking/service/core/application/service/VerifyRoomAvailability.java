package com.hotel.booking.system.booking.service.core.application.service;

import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.booking.service.core.domain.entity.BookingRoom;
import com.hotel.booking.system.booking.service.core.domain.entity.Room;
import com.hotel.booking.system.booking.service.core.ports.spi.repository.BookingRepository;
import com.hotel.booking.system.booking.service.core.ports.spi.repository.RoomRepository;
import com.hotel.booking.system.commons.core.application.annotation.DomainService;
import com.hotel.booking.system.commons.core.domain.valueobject.FailureMessages;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;
import lombok.extern.slf4j.Slf4j;

import java.text.MessageFormat;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Decide se os quartos de uma reserva ainda têm unidades livres no período pedido.
 *
 * <p>Um quarto é uma <em>categoria</em> com {@code quantity} unidades, e cada linha de reserva
 * consome {@code quantity} delas. O quarto está indisponível quando as unidades já ocupadas por
 * reservas ativas no período, somadas às que o pedido quer, passam do que o quarto tem.</p>
 *
 * <p><strong>Pressupõe uma transação aberta, e é ela que torna a decisão confiável.</strong>
 * Verificar e gravar são dois passos, e sem proteção duas reservas do último quarto leem a mesma
 * ocupação e passam as duas. A primeira coisa que {@link #execute} faz é travar os quartos
 * ({@link RoomRepository#lockAllByRoomId}); o lock dura até o commit de quem chamou, que também
 * grava a reserva. Quem espera o lock enxerga, ao acordar, a reserva que a primeira comitou.</p>
 *
 * <p><strong>O lock tem custo.</strong> Ele só é solto no commit do listener. A resposta entra no
 * outbox nessa transação; o confirm do broker é do relay. A espera está limitada por
 * {@code app.booking.room-lock-timeout} (10 s por tentativa). Estourar o prazo falha a sentença
 * e a transação desfaz, sem evento de falha de negócio.</p>
 */
@Slf4j
@DomainService
public class VerifyRoomAvailability {


  private static final String ROOM_UNAVAILABLE_TEMPLATE_MESSAGE = "The room roomId={0} is no longer available for period {1}";
  private final BookingRepository bookingRepository;
  private final RoomRepository roomRepository;

  public VerifyRoomAvailability(final BookingRepository bookingRepository, final RoomRepository roomRepository) {
    this.bookingRepository = bookingRepository;
    this.roomRepository = roomRepository;
  }

  /**
   * Trava os quartos do pedido sem ler a ocupação. Quem precisa decidir algo que depende do estado
   * da reserva antes de verificar a disponibilidade, como reconhecer um pedido repetido, chama
   * isto primeiro: sem o lock, essa decisão olha um estado que a transação concorrente do mesmo
   * quarto está prestes a mudar. Tomar o lock de novo em {@link #execute}, na mesma transação, é
   * imediato.
   */
  public void lockRooms(final Booking booking) {
    this.roomRepository.lockAllByRoomId(booking.getRoomsId());
  }

  public FailureMessages execute(final Booking booking) {
    final var failureMessages = FailureMessages.empty();

    final var rooms = this.roomRepository.lockAllByRoomId(booking.getRoomsId());
    final var requestedUnitsByRoom = this.requestedUnitsByRoom(booking);
    final var occupiedByRoom = this.bookingRepository.sumOccupiedUnits(
      rooms.stream().map(Room::getId).toList(),
      booking.getBookingPeriod()
    );

    log.info("Verifying rooms availability reservationOrderId={}", booking.getReservationOrderId());
    for (final var room : rooms) {
      final var requestedUnits = requestedUnitsByRoom.getOrDefault(room.getId(), 0);
      final var occupiedUnits = occupiedByRoom.getOrDefault(room.getId(), 0);
      if (occupiedUnits + requestedUnits > room.getQuantity()) {
        log.error(
          "The roomId={} is unavailable for period={}, reservationOrderId={}",
          room.getId(),
          booking.getBookingPeriod().periodAsString(),
          booking.getReservationOrderId()
        );
        failureMessages.add(MessageFormat.format(
          ROOM_UNAVAILABLE_TEMPLATE_MESSAGE,
          room.getId(),
          booking.getBookingPeriod().periodAsString()
        ));
      }
    }

    return failureMessages;
  }

  private Map<RoomId, Integer> requestedUnitsByRoom(final Booking booking) {
    return booking.getBookingRooms().stream()
      .collect(Collectors.groupingBy(BookingRoom::getRoomId, Collectors.summingInt(VerifyRoomAvailability::unitsOf)));
  }

  /**
   * Uma linha sem {@code quantity} vale zero unidades. O {@code BookingInitializer} recusa o
   * pedido antes do lock, então esta soma não é o caminho do pedido inválido. Quem chama
   * {@code execute} direto, sem essa validação, não pode tomar um {@code NullPointerException}
   * no lugar da recusa de domínio.
   */
  private static int unitsOf(final BookingRoom bookingRoom) {
    return Optional.ofNullable(bookingRoom.getQuantity()).orElse(0);
  }

}
