package com.hotel.booking.system.booking.service;

import com.hotel.booking.system.booking.service.application.configuration.*;
import com.hotel.booking.system.booking.service.core.application.dto.*;
import com.hotel.booking.system.booking.service.core.application.mapper.BookingUseCaseMapperImpl;
import com.hotel.booking.system.booking.service.core.application.messaging.*;
import com.hotel.booking.system.booking.service.core.application.service.*;
import com.hotel.booking.system.booking.service.core.application.usecase.*;
import com.hotel.booking.system.booking.service.core.domain.entity.*;
import com.hotel.booking.system.booking.service.core.domain.exception.*;
import com.hotel.booking.system.booking.service.core.ports.spi.repository.*;
import com.hotel.booking.system.booking.service.core.ports.spi.messaging.publisher.BookingRoomResponsePublisher;
import com.hotel.booking.system.commons.core.domain.event.*;
import com.hotel.booking.system.commons.core.domain.valueobject.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.SpringApplication;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Fluxos da aplicação de reservas")
class BookingApplicationTest {
  private final BookingRepository repository = mock(BookingRepository.class);
  private final RoomRepository rooms = mock(RoomRepository.class);
  private final BookingUseCaseMapperImpl mapper = new BookingUseCaseMapperImpl();
  private final BookingRoomResponsePublisher publisher = mock(BookingRoomResponsePublisher.class);
  private final String order = UUID.randomUUID().toString();
  private final String customer = UUID.randomUUID().toString();
  private final RoomId room = RoomId.of(UUID.randomUUID());
  private final LocalDate checkIn = LocalDate.of(2030, 1, 10);

  private BookingRoomRequestedEvent event(final BigDecimal total) {
    return BookingRoomRequestedEvent.builder().reservationOrderId(this.order).customerId(this.customer)
      .price(total).guests(3).checkIn(this.checkIn).checkOut(this.checkIn.plusDays(3))
      .rooms(List.of(BookingRoomItemRepresentation.builder().roomId(this.room.toString())
        .quantity(2).price(new BigDecimal("10.1234")).build())).build();
  }

  @Test
  @DisplayName("publica reserva pendente com identificadores, período e precisão")
  void reservaValidaPublicaPendenteComIdentificadoresPeriodoEPrecisao() {
    final var config = new BookingBeanConfiguration();
    final var mapper = config.bookingRoomUseCaseMapper();
    final var usecase = config.bookingRoomUseCase(this.repository, mapper, config.bookingInitializer(),
      config.verifyRoomAvailability(this.repository, this.rooms));
    config.bookingRoomRequestedHandler(usecase, mapper, this.publisher).handle(this.event(new BigDecimal("20.2468")));
    final var saved = ArgumentCaptor.forClass(Booking.class);
    verify(this.repository).save(saved.capture());
    final var booking = saved.getValue();
    assertThat(booking.getId()).isNotNull();
    assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
    assertThat(booking.getBookingRooms()).allSatisfy(item -> assertThat(item.getId()).isNotNull());
    final var sent = ArgumentCaptor.forClass(BookingRoomResponseEvent.class);
    verify(this.publisher).publish(sent.capture());
    assertThat(sent.getValue()).isInstanceOfSatisfying(BookingRoomPendingEvent.class, pending -> {
      assertThat(pending.getBookingRoomId()).isEqualTo(booking.getId().toString());
      assertThat(pending.getReservationOrderId()).isEqualTo(this.order);
      assertThat(pending.getCustomerId()).isEqualTo(this.customer);
      assertThat(pending.getStatus()).isEqualTo(CustomerReservationStatus.AWAITING_PAYMENT);
      assertThat(pending.getTotalPrice()).isEqualByComparingTo("20.2468");
      assertThat(pending.getCheckIn()).isEqualTo(this.checkIn);
      assertThat(pending.getCheckOut()).isEqualTo(this.checkIn.plusDays(3));
      assertThat(pending.getGuests()).as("lacuna atual: guests não é propagado").isNull();
      assertThat(pending.getRooms()).singleElement().satisfies(item -> {
        assertThat(item.getRoomId()).isEqualTo(this.room.toString());
        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.getPrice()).isEqualByComparingTo("10.1234");
      });
    });
  }

  @Test
  @DisplayName("publica falha sem persistir uma reserva inválida")
  void dadosInvalidosPublicamFalhaSemPersistir() {
    final var usecase = new BookingRoomUseCaseImpl(this.repository, this.mapper,
      new VerifyRoomAvailability(this.repository, this.rooms), new BookingInitializer());
    new BookingRoomRequestedHandlerImpl(usecase, this.mapper, this.publisher).handle(this.event(BigDecimal.ONE));
    verify(this.repository, never()).save(any());
    final var sent = ArgumentCaptor.forClass(BookingRoomResponseEvent.class);
    verify(this.publisher).publish(sent.capture());
    assertThat(sent.getValue()).isInstanceOfSatisfying(BookingRoomFailedEvent.class, failed -> {
      assertThat(failed.getFailureMessages()).containsExactly("The booking with reservationOrderId=" + this.order + " has inconsistent data");
      assertThat(failed.getStatus()).isEqualTo(CustomerReservationStatus.RESERVATION_FAILED);
      assertThat(failed.getCustomerId()).isEqualTo(this.customer);
      assertThat(failed.getCheckIn()).isEqualTo(this.checkIn);
      assertThat(failed.getCheckOut()).isEqualTo(this.checkIn.plusDays(3));
    });
  }

  @ParameterizedTest
  @DisplayName("altera o status e confirma somente após pagamento concluído")
  @ValueSource(booleans = {true, false})
  void alteraStatusEConfirmaSomentePagamentoConcluido(final boolean paid) {
    final var booking = this.mapper.bookingRoomInputToBooking(this.mapper.bookingRoomRequestedEventToBookingRoomInput(this.event(new BigDecimal("20.2468"))));
    booking.initialize();
    when(this.repository.findBookingByReservationOrderId(ReservationOrderId.of(this.order))).thenReturn(Optional.of(booking));
    final BookingRoomStatusUpdatedEvent event = paid
      ? BookingRoomPaymentCompleted.builder().reservationOrderId(this.order).customerId(this.customer).status(BookingStatus.CONFIRMED).build()
      : BookingRoomPaymentFailed.builder().reservationOrderId(this.order).customerId(this.customer).status(BookingStatus.CANCELED).failureMessages(List.of("declined")).build();
    final var config = new BookingBeanConfiguration();
    config.bookingRoomStatusChangedHandler(this.mapper, config.updateBookingRoomStatusUseCase(this.repository), this.publisher).handle(event);
    assertThat(booking.getStatus()).isEqualTo(event.getStatus());
    verify(this.repository).save(booking);
    if (paid) {
      final var sent = ArgumentCaptor.forClass(BookingRoomResponseEvent.class);
      verify(this.publisher).publish(sent.capture());
      assertThat(sent.getValue()).isInstanceOfSatisfying(BookingRoomConfirmedEvent.class, confirmed -> {
        assertThat(confirmed.getReservationOrderId()).isEqualTo(this.order);
        assertThat(confirmed.getCustomerId()).isEqualTo(this.customer);
        assertThat(confirmed.getStatus()).isEqualTo(CustomerReservationStatus.RESERVED);
      });
    } else {
      verifyNoInteractions(this.publisher);
    }
  }

  @Test
  @DisplayName("não persiste atualização de pedido inexistente")
  void pedidoInexistenteNaoPersiste() {
    assertThatThrownBy(() -> new UpdateBookingStatusUseCaseImpl(this.repository).execute(
      new UpdateBookingStatusInput(ReservationOrderId.of(this.order), CustomerId.of(this.customer), BookingStatus.CONFIRMED)))
      .isInstanceOf(BookingDomainException.class);
    verify(this.repository, never()).save(any());
  }

  /** A disponibilidade atual conta linhas de quartos, e não a soma das quantidades. */
  @Test
  @DisplayName("ignora reservas canceladas e períodos fora da consulta de disponibilidade")
  void disponibilidadeFiltraCanceladasEPeriodosEContaLinhas() {
    final var requested = this.mapper.bookingRoomInputToBooking(this.mapper.bookingRoomRequestedEventToBookingRoomInput(this.event(new BigDecimal("20.2468"))));
    final var occupied = Booking.builder().bookingPeriod(requested.getBookingPeriod()).status(BookingStatus.PENDING)
      .bookingRooms(requested.getBookingRooms()).build();
    final var canceled = Booking.builder().bookingPeriod(requested.getBookingPeriod()).status(BookingStatus.CANCELED)
      .bookingRooms(requested.getBookingRooms()).build();
    final var outside = Booking.builder().bookingPeriod(BookingPeriod.of(this.checkIn.plusDays(10), this.checkIn.plusDays(12)))
      .status(BookingStatus.CONFIRMED).bookingRooms(requested.getBookingRooms()).build();
    when(this.repository.findBookingByRoomIdAndPeriod(this.room, requested.getBookingPeriod())).thenReturn(List.of(occupied, canceled, outside));
    when(this.rooms.findAllByRoomId(List.of(this.room))).thenReturn(List.of(new Room(this.room, HotelId.of(UUID.randomUUID()), 3, 1)));
    final var service = new VerifyRoomAvailability(this.repository, this.rooms);
    assertThat(service.execute(requested).data()).containsExactly("The room roomId=" + this.room + " is no longer available for period 10/01/2030 - 13/01/2030");
    when(this.rooms.findAllByRoomId(List.of(this.room))).thenReturn(List.of(new Room(this.room, HotelId.of(UUID.randomUUID()), 3, 2)));
    assertThat(service.execute(requested).data()).isEmpty();
    when(this.repository.findBookingByRoomIdAndPeriod(this.room, requested.getBookingPeriod())).thenReturn(List.of(canceled, outside));
    assertThat(service.execute(requested).data()).isEmpty();
  }

  @Test
  @DisplayName("encaminha os argumentos ao iniciar o Spring Boot")
  void entrypointForwardsArgumentsToSpringBoot() {
    assertThat(new BookingServiceApplication()).isNotNull();
    try (final var spring = mockStatic(SpringApplication.class)) {
      final var args = new String[]{"--spring.profiles.active=test"};
      BookingServiceApplication.main(args);
      spring.verify(() -> SpringApplication.run(BookingServiceApplication.class, args));
    }
  }
}
