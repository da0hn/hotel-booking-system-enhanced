package com.hotel.booking.system.customer.service.core.application.usecase;

import com.hotel.booking.system.commons.core.domain.exception.InvalidIdException;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerId;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.customer.service.core.application.dto.ReservationOrderDetailInput;
import com.hotel.booking.system.customer.service.core.ports.api.mapper.CustomerUseCaseMapper;
import com.hotel.booking.system.customer.service.core.ports.api.usecase.GetCustomerReservationOrderDetail;
import com.hotel.booking.system.customer.service.core.ports.spi.repository.CustomerRepository;
import com.hotel.booking.system.customer.service.core.ports.spi.repository.ReservationOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tags({@Tag("unit")})
@DisplayName("Get customer reservation order detail use case tests")
@ExtendWith(MockitoExtension.class)
class GetCustomerReservationOrderDetailImplTest {

  private static final String CUSTOMER_ID = "f1e28a47-8852-45e8-b8e1-e6701633dd56";

  private static final String RESERVATION_ORDER_ID = "0f2d2f0e-52bb-4d1d-8d77-3a3f7d6d1c11";

  private GetCustomerReservationOrderDetail sut;

  @Mock
  private CustomerRepository customerRepository;

  @Mock
  private ReservationOrderRepository reservationOrderRepository;

  @Mock
  private CustomerUseCaseMapper customerUseCaseMapper;

  @BeforeEach
  void setUp() {
    this.sut = new GetCustomerReservationOrderDetailImpl(
      this.customerRepository,
      this.reservationOrderRepository,
      this.customerUseCaseMapper
    );
  }

  @Test
  @DisplayName("Should refuse a customerId that is not a UUID without querying any repository")
  void test1() {
    final var input = new ReservationOrderDetailInput("abc", RESERVATION_ORDER_ID);

    assertThatThrownBy(() -> this.sut.execute(input))
      .isInstanceOfSatisfying(InvalidIdException.class, exception ->
        assertThat(exception.getField()).isEqualTo("customerId"));

    Mockito.verifyNoInteractions(this.customerRepository, this.reservationOrderRepository);
  }

  @Test
  @DisplayName("Should refuse a reservationOrderId that is not a UUID before looking the customer up")
  void test2() {
    final var input = new ReservationOrderDetailInput(CUSTOMER_ID, "xyz");

    assertThatThrownBy(() -> this.sut.execute(input))
      .isInstanceOfSatisfying(InvalidIdException.class, exception ->
        assertThat(exception.getField()).isEqualTo("reservationOrderId"));

    Mockito.verifyNoInteractions(this.customerRepository, this.reservationOrderRepository);
  }

  @Test
  @DisplayName("Should report only the first refused field when both ids are malformed")
  void test3() {
    final var input = new ReservationOrderDetailInput("abc", "xyz");

    assertThatThrownBy(() -> this.sut.execute(input))
      .isInstanceOfSatisfying(InvalidIdException.class, exception ->
        assertThat(exception.getField()).isEqualTo("customerId"));
  }

  @Test
  @DisplayName("Should still query both repositories when the ids are valid UUIDs")
  void test4() {
    this.sut.execute(new ReservationOrderDetailInput(CUSTOMER_ID, RESERVATION_ORDER_ID));

    Mockito.verify(this.customerRepository).findById(CustomerId.of(CUSTOMER_ID));
    Mockito.verify(this.reservationOrderRepository).findById(ReservationOrderId.of(RESERVATION_ORDER_ID));
  }
}
