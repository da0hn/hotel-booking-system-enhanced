package com.hotel.booking.system.hotel.service.application.web.advice;

import com.hotel.booking.system.commons.core.application.exception.MessagePublishException;
import com.hotel.booking.system.hotel.service.application.service.impl.HotelApplicationServiceImpl;
import com.hotel.booking.system.hotel.service.application.web.controller.HotelController;
import com.hotel.booking.system.hotel.service.core.application.dto.BookingRoomInput;
import com.hotel.booking.system.hotel.service.core.application.dto.BookingRoomOutput;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.BookingRoomRequestUseCase;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.RegisterHotelUseCase;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.SearchHotelAvailableUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Tratamento de exceções na fronteira HTTP do serviço de hotéis")
class HotelExceptionHandlerTest {

  private static final String BOOKING_BODY = """
    {"customerId":"customer","hotelId":"hotel","guests":1,"rooms":[]}
    """;

  private final BookingRoomRequestUseCase book = mock(BookingRoomRequestUseCase.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    final var service = new HotelApplicationServiceImpl(
      mock(RegisterHotelUseCase.class),
      mock(SearchHotelAvailableUseCase.class),
      this.book
    );
    this.mockMvc = MockMvcBuilders.standaloneSetup(new HotelController(service))
      .setControllerAdvice(new HotelExceptionHandler())
      .build();
  }

  @Test
  @DisplayName("responde 503 com o envelope de erro quando a mensagem não chega ao broker")
  void responde503QuandoOEventoNaoChegaAoBroker() throws Exception {
    when(this.book.execute(any(BookingRoomInput.class)))
      .thenThrow(new MessagePublishException("Failed to publish BookingRoomRequestedEvent to exchange=exchange.booking-room"));

    this.mockMvc.perform(post("/hotel/booking").contentType(MediaType.APPLICATION_JSON).content(BOOKING_BODY))
      .andExpect(status().isServiceUnavailable())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("hotel.booking.temporarily.unavailable"))
      .andExpect(jsonPath("$.time").isString());
  }

  @Test
  @DisplayName("não expõe ao cliente o exchange nem o motivo da falha do broker")
  void naoVazaDetalheDeInfraestruturaNaResposta() throws Exception {
    when(this.book.execute(any(BookingRoomInput.class)))
      .thenThrow(new MessagePublishException("Failed to publish X to exchange=exchange.booking-room: connection refused"));

    final var body = this.mockMvc.perform(post("/hotel/booking").contentType(MediaType.APPLICATION_JSON).content(BOOKING_BODY))
      .andReturn()
      .getResponse()
      .getContentAsString();

    assertThat(body).doesNotContain("exchange").doesNotContain("connection refused");
  }

  @Test
  @DisplayName("mantém 200 com o identificador da reserva quando a publicação funciona")
  void mantem200QuandoAPublicacaoFunciona() throws Exception {
    final var reservationOrderId = UUID.randomUUID();
    when(this.book.execute(any(BookingRoomInput.class))).thenReturn(new BookingRoomOutput(reservationOrderId));

    this.mockMvc.perform(post("/hotel/booking").contentType(MediaType.APPLICATION_JSON).content(BOOKING_BODY))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true));
  }

  @Test
  @DisplayName("não trata outras exceções: a generalização do advice é da issue #9")
  void naoTrataExcecoesQueNaoSaoDeFalhaDePublicacao() {
    when(this.book.execute(any(BookingRoomInput.class))).thenThrow(new IllegalStateException("outra falha"));

    assertThatThrownBy(() -> this.mockMvc.perform(
      post("/hotel/booking").contentType(MediaType.APPLICATION_JSON).content(BOOKING_BODY)
    )).hasRootCauseInstanceOf(IllegalStateException.class);
  }
}
