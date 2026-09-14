package com.hotel.booking.system.customer.service.infrastructure.configuration;

import com.hotel.booking.system.customer.service.core.application.messaging.CustomerBookingStatusUpdatedHandlerImpl;
import com.hotel.booking.system.customer.service.core.ports.api.mapper.CustomerUseCaseMapper;
import com.hotel.booking.system.customer.service.core.ports.api.messaging.CustomerBookingStatusUpdatedHandler;
import com.hotel.booking.system.customer.service.core.ports.api.usecase.GetCustomerReservationOrderDetail;
import com.hotel.booking.system.customer.service.core.ports.api.usecase.InitializeCustomerBookingUseCase;
import com.hotel.booking.system.customer.service.core.ports.api.usecase.UpdateCustomerBookingFailureStatusUseCase;
import com.hotel.booking.system.customer.service.core.ports.api.usecase.UpdateCustomerBookingStatusUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomerBeanConfiguration {

  @Bean
  public CustomerBookingStatusUpdatedHandler customerBookingStatusUpdatedHandler(
    final InitializeCustomerBookingUseCase initializeCustomerBookingUseCase,
    final UpdateCustomerBookingStatusUseCase updateCustomerBookingStatusUseCase,
    final UpdateCustomerBookingFailureStatusUseCase updateCustomerBookingFailureStatusUseCase,
    final CustomerUseCaseMapper customerUseCaseMapper
  ) {
    return new CustomerBookingStatusUpdatedHandlerImpl(
      initializeCustomerBookingUseCase,
      updateCustomerBookingStatusUseCase,
      updateCustomerBookingFailureStatusUseCase,
      customerUseCaseMapper
    );
  }

}
