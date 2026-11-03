package com.hotel.booking.system.booking.service.core.application.service;

import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.booking.service.core.domain.entity.BookingRoom;
import com.hotel.booking.system.booking.service.core.domain.entity.Room;
import com.hotel.booking.system.booking.service.core.ports.spi.repository.BookingRepository;
import com.hotel.booking.system.booking.service.core.ports.spi.repository.RoomRepository;
import com.hotel.booking.system.commons.core.application.annotation.DomainService;
import com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus;
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
 * <p><strong>O lock tem custo, e o teto dele não está no nosso código.</strong> Ele só é solto no
 * commit do listener, e até lá a mesma transação ainda grava a reserva e publica o evento de
 * resposta esperando o confirm do broker: até 5 s por tentativa, em até 3 tentativas, cada uma
 * numa transação própria. Com o broker saudável isso leva milissegundos; com ele lento, cada
 * pedido do mesmo quarto espera o anterior, e não há {@code lock_timeout} que limite a espera.
 * Publicar só depois do commit, que encurtaria a seção crítica, depende do Outbox (#35).</p>
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

    log.info("Verifying rooms availability reservationOrderId={}", booking.getReservationOrderId());
    for (final var room : rooms) {
      final var requestedUnits = requestedUnitsByRoom.getOrDefault(room.getId(), 0);
      final var occupiedUnits = this.occupiedUnits(room, booking);
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

  private int occupiedUnits(final Room room, final Booking booking) {
    return this.bookingRepository.findBookingByRoomIdAndPeriod(room.getId(), booking.getBookingPeriod()).stream()
      .filter(item -> item.getStatus() != BookingStatus.CANCELED)
      .filter(item -> booking.isBookingPeriodContainedIn(item.getBookingPeriod()))
      .flatMap(item -> item.getBookingRooms().stream())
      .filter(item -> room.getId().equals(item.getRoomId()))
      .mapToInt(VerifyRoomAvailability::unitsOf)
      .sum();
  }

  /**
   * Uma linha sem {@code quantity} vale zero unidades. O {@code BookingInitializer} recusa o
   * pedido cuja linha tem quantidade nula, zero ou negativa ({@code BookingRoom.validate()}),
   * mas o caso de uso verifica a disponibilidade mesmo assim, antes de olhar esse resultado.
   * Um {@code NullPointerException} aqui trocaria a recusa limpa por uma mensagem indo para a
   * DLQ. Uma quantidade negativa também passa por esta soma, sem efeito: o pedido já saiu da
   * validação com falha e não chega a ser gravado.
   */
  private static int unitsOf(final BookingRoom bookingRoom) {
    return Optional.ofNullable(bookingRoom.getQuantity()).orElse(0);
  }

}
