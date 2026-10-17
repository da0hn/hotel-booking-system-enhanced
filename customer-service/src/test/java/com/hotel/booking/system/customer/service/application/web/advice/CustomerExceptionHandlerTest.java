package com.hotel.booking.system.customer.service.application.web.advice;

import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import com.hotel.booking.system.customer.service.application.service.CustomerApplicationService;
import com.hotel.booking.system.customer.service.application.web.controller.CustomerController;
import com.hotel.booking.system.customer.service.core.application.dto.ReservationOrderDetailOutput;
import com.hotel.booking.system.customer.service.core.domain.exception.CustomerDomainException;
import com.hotel.booking.system.customer.service.core.domain.exception.CustomerNotFoundException;
import com.hotel.booking.system.customer.service.core.domain.exception.ReservationOrderNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Tratamento de exceções na fronteira HTTP do serviço de clientes")
class CustomerExceptionHandlerTest {

  private static final String CUSTOMER_ID = UUID.randomUUID().toString();

  private static final String RESERVATION_ORDER_ID = UUID.randomUUID().toString();

  private static final String URL = "/customers/{customerId}/reservation-order/{reservationOrderId}";

  private final CustomerApplicationService service = mock(CustomerApplicationService.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    this.mockMvc = MockMvcBuilders.standaloneSetup(new CustomerController(this.service))
      .setControllerAdvice(new CustomerExceptionHandler())
      .build();
  }

  @Test
  @DisplayName("responde 404 com o envelope de erro quando a ordem de reserva não existe")
  void ordemDeReservaInexistenteResponde404() throws Exception {
    when(this.service.getCustomerReservationOrderDetail(CUSTOMER_ID, RESERVATION_ORDER_ID))
      .thenThrow(new ReservationOrderNotFoundException());

    this.mockMvc.perform(get(URL, CUSTOMER_ID, RESERVATION_ORDER_ID))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("customer.reservation-order.not.found"))
      .andExpect(jsonPath("$.data").doesNotExist())
      .andExpect(jsonPath("$.time").isString());
  }

  @Test
  @DisplayName("responde 404 com o envelope de erro quando o cliente não existe")
  void clienteInexistenteResponde404() throws Exception {
    when(this.service.getCustomerReservationOrderDetail(CUSTOMER_ID, RESERVATION_ORDER_ID))
      .thenThrow(new CustomerNotFoundException());

    this.mockMvc.perform(get(URL, CUSTOMER_ID, RESERVATION_ORDER_ID))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("customer.not.found"));
  }

  @Test
  @DisplayName("responde 422 com a chave de negócio quando outra regra de domínio é violada")
  void regraDeDominioViolada422() throws Exception {
    when(this.service.getCustomerReservationOrderDetail(CUSTOMER_ID, RESERVATION_ORDER_ID))
      .thenThrow(new CustomerDomainException(ApplicationMessage.CUSTOMER_RESERVATION_ORDER_STATUS_INVALID_STATE));

    this.mockMvc.perform(get(URL, CUSTOMER_ID, RESERVATION_ORDER_ID))
      .andExpect(status().isUnprocessableContent())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("customer.reservation-order.status.invalid-state"));
  }

  @Test
  @DisplayName("responde 500 com mensagem genérica, sem expor o detalhe, quando o banco falha")
  void falhaInesperadaResponde500SemVazarDetalhe() throws Exception {
    when(this.service.getCustomerReservationOrderDetail(CUSTOMER_ID, RESERVATION_ORDER_ID))
      .thenThrow(new IllegalStateException("could not connect to jdbc:postgresql://db:5432/hotel"));

    final var body = this.mockMvc.perform(get(URL, CUSTOMER_ID, RESERVATION_ORDER_ID))
      .andExpect(status().isInternalServerError())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("internal.server.error"))
      .andReturn()
      .getResponse()
      .getContentAsString();

    assertThat(body).doesNotContain("jdbc").doesNotContain("IllegalStateException");
  }

  @Test
  @DisplayName("não interfere na resposta de sucesso: 200 com o envelope de sucesso")
  void respostaDeSucessoNaoEAlterada() throws Exception {
    when(this.service.getCustomerReservationOrderDetail(CUSTOMER_ID, RESERVATION_ORDER_ID))
      .thenReturn(ReservationOrderDetailOutput.builder()
        .customerId(CUSTOMER_ID)
        .reservationOrderId(RESERVATION_ORDER_ID)
        .status(CustomerReservationStatus.AWAITING_PAYMENT)
        .build());

    this.mockMvc.perform(get(URL, CUSTOMER_ID, RESERVATION_ORDER_ID))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true))
      .andExpect(jsonPath("$.data.reservationOrderId").value(RESERVATION_ORDER_ID));
  }
}
