package com.hotel.booking.system.booking.service.infrastructure.configuration;

import com.hotel.booking.system.booking.service.core.application.messaging.BookingRoomRequestedHandlerImpl;
import com.hotel.booking.system.booking.service.core.application.messaging.BookingRoomStatusChangedHandlerImpl;
import com.hotel.booking.system.booking.service.core.ports.api.mapper.BookingUseCaseMapper;
import com.hotel.booking.system.booking.service.core.ports.api.messaging.BookingRoomRequestedHandler;
import com.hotel.booking.system.booking.service.core.ports.api.messaging.BookingRoomStatusChangedHandler;
import com.hotel.booking.system.booking.service.core.ports.api.usecase.BookingRoomUseCase;
import com.hotel.booking.system.booking.service.core.ports.api.usecase.UpdateBookingStatusUseCase;
import com.hotel.booking.system.booking.service.core.ports.spi.messaging.publisher.BookingRoomResponsePublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BookingBeanConfiguration {

  @Bean
  public BookingRoomRequestedHandler bookingRoomRequestedHandler(
    final BookingRoomUseCase bookingRoomUseCase,
    final BookingUseCaseMapper bookingUseCaseMapper,
    final BookingRoomResponsePublisher bookingRoomResponsePublisher
  ) {
    return new BookingRoomRequestedHandlerImpl(
      bookingRoomUseCase,
      bookingUseCaseMapper,
      bookingRoomResponsePublisher
    );
  }

  @Bean
  public BookingRoomStatusChangedHandler bookingRoomStatusChangedHandler(
    final BookingUseCaseMapper bookingUseCaseMapper,
    final UpdateBookingStatusUseCase updateBookingRoomStatusUseCase,
    final BookingRoomResponsePublisher bookingRoomResponsePublisher
  ) {
    return new BookingRoomStatusChangedHandlerImpl(bookingUseCaseMapper, updateBookingRoomStatusUseCase, bookingRoomResponsePublisher);
  }

}
