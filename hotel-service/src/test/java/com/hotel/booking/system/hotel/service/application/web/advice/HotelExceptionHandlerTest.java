package com.hotel.booking.system.hotel.service.application.web.advice;

import com.hotel.booking.system.commons.core.application.exception.MessagePublishException;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import com.hotel.booking.system.hotel.service.application.service.impl.HotelApplicationServiceImpl;
import com.hotel.booking.system.hotel.service.application.web.controller.HotelController;
import com.hotel.booking.system.hotel.service.core.application.dto.BookingRoomInput;
import com.hotel.booking.system.hotel.service.core.application.dto.BookingRoomOutput;
import com.hotel.booking.system.hotel.service.core.application.dto.RegisterHotelInput;
import com.hotel.booking.system.hotel.service.core.domain.exception.HotelDomainException;
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

  private final RegisterHotelUseCase register = mock(RegisterHotelUseCase.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    final var service = new HotelApplicationServiceImpl(
      this.register,
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
  @DisplayName("responde 422 com a chave de negócio quando os hóspedes excedem a capacidade dos quartos")
  void responde422QuandoOsHospedesExcedemACapacidade() throws Exception {
    when(this.book.execute(any(BookingRoomInput.class)))
      .thenThrow(new HotelDomainException(ApplicationMessage.HOTEL_BOOKING_GUESTS_EXCEEDED));

    this.mockMvc.perform(post("/hotel/booking").contentType(MediaType.APPLICATION_JSON).content(BOOKING_BODY))
      .andExpect(status().isUnprocessableContent())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("hotel.booking.guests.exceeded"))
      .andExpect(jsonPath("$.data").doesNotExist())
      .andExpect(jsonPath("$.time").isString());
  }

  @Test
  @DisplayName("responde 422, e não 404, quando um quarto citado no corpo da requisição não existe")
  void responde422QuandoOQuartoDoCorpoNaoExiste() throws Exception {
    when(this.book.execute(any(BookingRoomInput.class)))
      .thenThrow(new HotelDomainException(ApplicationMessage.HOTEL_ROOM_NOT_FOUND));

    this.mockMvc.perform(post("/hotel/booking").contentType(MediaType.APPLICATION_JSON).content(BOOKING_BODY))
      .andExpect(status().isUnprocessableContent())
      .andExpect(jsonPath("$.message").value("hotel.room.not.found"));
  }

  @Test
  @DisplayName("responde 422 também no cadastro de hotel quando uma regra de domínio é violada")
  void responde422NoCadastroQuandoUmaRegraDeDominioEViolada() throws Exception {
    when(this.register.execute(any(RegisterHotelInput.class)))
      .thenThrow(new HotelDomainException(ApplicationMessage.HOTEL_CATEGORY_NOT_FOUND));

    this.mockMvc.perform(post("/hotel").contentType(MediaType.APPLICATION_JSON).content("{}"))
      .andExpect(status().isUnprocessableContent())
      .andExpect(jsonPath("$.message").value("hotel.category.not.found"));
  }

  @Test
  @DisplayName("responde 500 com mensagem genérica quando a exceção é inesperada")
  void excecaoInesperadaResponde500ComMensagemGenerica() throws Exception {
    when(this.book.execute(any(BookingRoomInput.class)))
      .thenThrow(new IllegalStateException("connection to jdbc:postgresql://db:5432 refused"));

    final var body = this.mockMvc.perform(post("/hotel/booking").contentType(MediaType.APPLICATION_JSON).content(BOOKING_BODY))
      .andExpect(status().isInternalServerError())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("internal.server.error"))
      .andReturn()
      .getResponse()
      .getContentAsString();

    assertThat(body).doesNotContain("jdbc").doesNotContain("IllegalStateException");
  }

  @Test
  @DisplayName("responde 400 com o envelope de erro quando o corpo da requisição é ilegível")
  void corpoIlegivelResponde400ComEnvelope() throws Exception {
    this.mockMvc.perform(post("/hotel/booking").contentType(MediaType.APPLICATION_JSON).content("{"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("request.invalid"));
  }
}
