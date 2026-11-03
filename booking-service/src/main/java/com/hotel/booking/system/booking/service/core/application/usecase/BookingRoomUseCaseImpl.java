package com.hotel.booking.system.booking.service.core.application.usecase;

import com.hotel.booking.system.commons.core.application.annotation.UseCase;
import com.hotel.booking.system.booking.service.core.application.dto.BookingRoomInput;
import com.hotel.booking.system.booking.service.core.application.dto.BookingRoomOutput;
import com.hotel.booking.system.booking.service.core.application.service.BookingInitializer;
import com.hotel.booking.system.booking.service.core.application.service.VerifyRoomAvailability;
import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.booking.service.core.ports.api.mapper.BookingUseCaseMapper;
import com.hotel.booking.system.booking.service.core.ports.api.usecase.BookingRoomUseCase;
import com.hotel.booking.system.booking.service.core.ports.spi.repository.BookingRepository;
import com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import com.hotel.booking.system.commons.core.domain.valueobject.FailureMessages;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
@UseCase
public class BookingRoomUseCaseImpl implements BookingRoomUseCase {

  /**
   * Um mapa, e não um {@code switch} sobre o enum: o {@code switch} faz o javac gerar uma classe
   * sintética ({@code BookingRoomUseCaseImpl$1}) neste pacote, e a regra de arquitetura exige
   * {@code @UseCase} em tudo que mora nele. O teste que percorre {@code BookingStatus.values()}
   * cobra o lugar de um valor novo no enum.
   */
  private static final Map<BookingStatus, CustomerReservationStatus> REPORTED_STATUS = Map.of(
    BookingStatus.PENDING, CustomerReservationStatus.AWAITING_PAYMENT,
    BookingStatus.CONFIRMED, CustomerReservationStatus.RESERVED,
    BookingStatus.CANCELED, CustomerReservationStatus.PAYMENT_FAILED
  );

  private final BookingRepository bookingRepository;
  private final BookingUseCaseMapper mapper;
  private final VerifyRoomAvailability verifyRoomAvailability;
  private final BookingInitializer bookingInitializer;

  public BookingRoomUseCaseImpl(
    final BookingRepository bookingRepository,
    final BookingUseCaseMapper mapper,
    final VerifyRoomAvailability verifyRoomAvailability,
    final BookingInitializer bookingInitializer
  ) {
    this.bookingRepository = bookingRepository;
    this.mapper = mapper;
    this.verifyRoomAvailability = verifyRoomAvailability;
    this.bookingInitializer = bookingInitializer;
  }

  private void initializeAndValidate(final Booking booking, final FailureMessages failureMessages) {
    final var collectedFailureMessages = this.bookingInitializer.execute(booking);
    failureMessages.addAll(collectedFailureMessages);
  }

  /**
   * Idempotente pela chave de negócio: se o pedido já tem reserva, devolve a existente, marcada
   * como repetida, em vez de gravar outra. A busca vem <em>antes</em> da verificação de
   * disponibilidade de propósito — depois dela, a reserva do primeiro pedido contaria como
   * ocupação do quarto e a repetição seria respondida como "indisponível", derrubando na saga
   * um pedido que está reservado.
   *
   * <p>E vem <em>depois</em> do lock dos quartos, também de propósito. Sem o lock, duas entregas
   * do mesmo pedido passam pela busca sem ver nada, e a segunda, ao acordar do lock que a
   * verificação toma, enxerga a reserva da primeira como ocupação e cai no mesmo "indisponível".
   * Com o lock antes, a segunda espera a primeira comitar e então a encontra. A constraint
   * {@code booking_reservation_order_id_uk} segue como rede de segurança do banco.</p>
   */
  @Override
  public BookingRoomOutput execute(final BookingRoomInput input) {
    final var booking = this.mapper.bookingRoomInputToBooking(input);
    this.verifyRoomAvailability.lockRooms(booking);
    return this.bookingRepository.findBookingByReservationOrderId(booking.getReservationOrderId())
      .map(this::duplicateOf)
      .orElseGet(() -> this.create(booking));
  }

  private BookingRoomOutput create(final Booking booking) {
    final var failureMessages = FailureMessages.empty();
    this.initializeAndValidate(booking, failureMessages);
    this.verifyRoomAvailability(booking, failureMessages);
    if (failureMessages.isNotEmpty()) {
      return new BookingRoomOutput(booking, CustomerReservationStatus.RESERVATION_FAILED, failureMessages);
    }
    this.bookingRepository.save(booking);
    return new BookingRoomOutput(booking, CustomerReservationStatus.AWAITING_PAYMENT, failureMessages);
  }

  private BookingRoomOutput duplicateOf(final Booking existing) {
    log.info(
      "A booking already exists for the reservation order, ignoring duplicate request | reservationOrderId={} | status={}",
      existing.getReservationOrderId(),
      existing.getStatus()
    );
    return new BookingRoomOutput(existing, this.statusOf(existing), FailureMessages.empty(), true);
  }

  private CustomerReservationStatus statusOf(final Booking booking) {
    return REPORTED_STATUS.get(booking.getStatus());
  }

  private void verifyRoomAvailability(final Booking booking, final FailureMessages failureMessages) {
    final var collectedFailureMessages = this.verifyRoomAvailability.execute(booking);
    failureMessages.addAll(collectedFailureMessages);
  }
}
