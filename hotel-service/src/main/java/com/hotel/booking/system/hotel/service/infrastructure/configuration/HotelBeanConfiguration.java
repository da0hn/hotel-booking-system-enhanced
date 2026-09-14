package com.hotel.booking.system.hotel.service.infrastructure.configuration;

import com.hotel.booking.system.hotel.service.core.application.messaging.BookingRoomResponseHandlerImpl;
import com.hotel.booking.system.hotel.service.core.application.messaging.PaymentResponseHandlerImpl;
import com.hotel.booking.system.hotel.service.core.ports.api.messaging.BookingRoomResponseHandler;
import com.hotel.booking.system.hotel.service.core.ports.api.messaging.PaymentResponseHandler;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.BookingRoomRequestedPublisher;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.BookingRoomStatusChangedPublisher;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.CustomerBookingRoomStatusUpdatedPublisher;
import com.hotel.booking.system.hotel.service.core.ports.spi.messaging.publisher.PaymentRequestedPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HotelBeanConfiguration {

  @Bean
  public BookingRoomResponseHandler bookingRoomResponseHandler(
    final CustomerBookingRoomStatusUpdatedPublisher customerBookingRoomUpdatedPublisher,
    final PaymentRequestedPublisher paymentRequestedPublisher
  ) {
    return new BookingRoomResponseHandlerImpl(customerBookingRoomUpdatedPublisher, paymentRequestedPublisher);
  }

  @Bean
  public PaymentResponseHandler paymentResponseHandler(
    final CustomerBookingRoomStatusUpdatedPublisher customerBookingRoomStatusUpdatedPublisher,
    final BookingRoomStatusChangedPublisher bookingRoomStatusChangedPublisher
  ) {
    return new PaymentResponseHandlerImpl(customerBookingRoomStatusUpdatedPublisher, bookingRoomStatusChangedPublisher);
  }
}
