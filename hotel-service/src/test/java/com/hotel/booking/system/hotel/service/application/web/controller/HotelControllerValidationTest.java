package com.hotel.booking.system.hotel.service.application.web.controller;

import com.hotel.booking.system.hotel.service.application.service.impl.HotelApplicationServiceImpl;
import com.hotel.booking.system.hotel.service.application.web.advice.HotelExceptionHandler;
import com.hotel.booking.system.hotel.service.core.application.dto.BookingRoomInput;
import com.hotel.booking.system.hotel.service.core.application.dto.BookingRoomOutput;
import com.hotel.booking.system.hotel.service.core.application.dto.RegisterHotelInput;
import com.hotel.booking.system.hotel.service.core.application.dto.RegisterHotelOutput;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.BookingRoomRequestUseCase;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.RegisterHotelUseCase;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.SearchHotelAvailableUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Validação da entrada na fronteira HTTP do serviço de hotéis")
class HotelControllerValidationTest {

  private static final String CUSTOMER_ID = "f1e28a47-8852-45e8-b8e1-e6701633dd56";

  private static final String HOTEL_ID = "b69b768f-32d8-4c70-a3ef-b5ace438c5e7";

  private static final String ROOM_ID = "e86ff4c5-6f20-419e-8ce8-3140db9bd7a7";

  private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private static final String VALID_REGISTER = """
    {"name":"Pousada Portal","description":"Pousada no centro","categoryId":"%s","street":"Av Perimetral, 766",
     "cep":"78195-000","localityId":"%s",
     "rooms":[{"name":"Duplo","description":"Quarto duplo","capacity":2,"currentPrice":350,"quantity":2}]}
    """.formatted(HOTEL_ID, ROOM_ID);

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
  @DisplayName("reserva inválida responde 400 com a lista completa de campos e não chega ao caso de uso")
  void reservaInvalidaListaTodosOsCampos() throws Exception {
    final var body = """
      {"customerId":"","hotelId":"%s","guests":0,"checkIn":"10/09/2020","checkOut":"08/09/2020","rooms":[]}
      """.formatted(HOTEL_ID);

    this.booking(body)
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("request.invalid"))
      .andExpect(jsonPath("$.data[*].field", containsInAnyOrder(
        "customerId", "guests", "checkIn", "rooms", "checkOutAfterCheckIn"
      )))
      .andExpect(jsonPath("$.data[?(@.field == 'customerId')].message").value("booking.customer.must.not.be.null"))
      .andExpect(jsonPath("$.data[?(@.field == 'guests')].message")
        .value("hotel.booking.guests.must.not.be.zero-or-negative"))
      .andExpect(jsonPath("$.data[?(@.field == 'checkIn')].message")
        .value("booking.period.check-in.must.not.be.in.the.past"))
      .andExpect(jsonPath("$.data[?(@.field == 'rooms')].message")
        .value("hotel.booking.rooms.must.not.be.null-or-empty"))
      .andExpect(jsonPath("$.data[?(@.field == 'checkOutAfterCheckIn')].message")
        .value("booking.period.check-out.must.be.after.check-in"));

    verifyNoInteractions(this.book);
  }

  @Test
  @DisplayName("reserva sem nenhum campo responde 400 listando todos os obrigatórios")
  void reservaVaziaListaOsObrigatorios() throws Exception {
    this.booking("{}")
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.data[*].field", containsInAnyOrder(
        "customerId", "hotelId", "guests", "checkIn", "checkOut", "rooms"
      )));

    verifyNoInteractions(this.book);
  }

  @Test
  @DisplayName("item de quarto sem id e com quantidade negativa é recusado com o campo do item")
  void itemDeQuartoInvalido() throws Exception {
    this.booking(this.bookingWithRooms("""
      [{"roomId":"","roomQuantity":-1}]
      """))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.data[*].field", containsInAnyOrder("rooms[].roomId", "rooms[].roomQuantity")))
      .andExpect(jsonPath("$.data[?(@.field == 'rooms[].roomId')].message").value("hotel.room.must.not.be.null"))
      .andExpect(jsonPath("$.data[?(@.field == 'rooms[].roomQuantity')].message")
        .value("hotel.room.quantity.must.not.be.zero-or-negative"));

    verifyNoInteractions(this.book);
  }

  @Test
  @DisplayName("check-out igual ao check-in é recusado, porque estadia de 0 noites é inválida")
  void checkOutIgualAoCheckInEhRecusado() throws Exception {
    final var day = LocalDate.now().plusDays(3).format(FORMAT);

    this.booking(this.bookingWithDates(day, day))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.data[*].field", containsInAnyOrder("checkOutAfterCheckIn")));
  }

  @Test
  @DisplayName("aceita check-in hoje, o limite de @FutureOrPresent, e segue para o caso de uso")
  void aceitaCheckInHoje() throws Exception {
    when(this.book.execute(any(BookingRoomInput.class))).thenReturn(new BookingRoomOutput(UUID.randomUUID()));

    this.booking(this.bookingWithDates(
      LocalDate.now().format(FORMAT),
      LocalDate.now().plusDays(1).format(FORMAT)
    ))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true));

    verify(this.book).execute(any(BookingRoomInput.class));
  }

  @Test
  @DisplayName("reserva válida segue para o caso de uso e responde 200")
  void reservaValidaResponde200() throws Exception {
    when(this.book.execute(any(BookingRoomInput.class))).thenReturn(new BookingRoomOutput(UUID.randomUUID()));

    this.booking(this.bookingWithDates("10/01/2030", "13/01/2030"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true));

    verify(this.book).execute(any(BookingRoomInput.class));
  }

  @Test
  @DisplayName("cadastro sem nenhum campo responde 400 listando todos os obrigatórios")
  void cadastroVazioListaOsObrigatorios() throws Exception {
    this.registerHotel("{}")
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.message").value("request.invalid"))
      .andExpect(jsonPath("$.data[*].field", containsInAnyOrder(
        "name", "description", "categoryId", "street", "cep", "localityId", "rooms"
      )))
      .andExpect(jsonPath("$.data[?(@.field == 'name')].message")
        .value("hotel.name.must.not.be.null-or-empty"))
      .andExpect(jsonPath("$.data[?(@.field == 'rooms')].message")
        .value("hotel.rooms.must.not.be.null-or-empty"));

    verifyNoInteractions(this.register);
  }

  @Test
  @DisplayName("quarto do cadastro sem campos é recusado com o caminho do item da lista")
  void quartoDoCadastroVazio() throws Exception {
    this.registerHotel(VALID_REGISTER.replaceFirst("\"rooms\":\\[.*]", "\"rooms\":[{}]"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.data[*].field", containsInAnyOrder(
        "rooms[0].name", "rooms[0].description", "rooms[0].capacity", "rooms[0].currentPrice", "rooms[0].quantity"
      )));

    verifyNoInteractions(this.register);
  }

  @Test
  @DisplayName("quarto do cadastro com capacidade, preço e quantidade zerados é recusado")
  void quartoDoCadastroComValoresZerados() throws Exception {
    this.registerHotel(VALID_REGISTER.replaceFirst(
        "\"rooms\":\\[.*]",
        "\"rooms\":[{\"name\":\"A\",\"description\":\"B\",\"capacity\":0,\"currentPrice\":0,\"quantity\":0}]"
      ))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.data[?(@.field == 'rooms[0].capacity')].message")
        .value("hotel.room.capacity.must.not.be.zero-or-negative"))
      .andExpect(jsonPath("$.data[?(@.field == 'rooms[0].currentPrice')].message")
        .value("hotel.room.current-price.must.not.be.zero-or-negative"))
      .andExpect(jsonPath("$.data[?(@.field == 'rooms[0].quantity')].message")
        .value("hotel.room.quantity.must.not.be.zero-or-negative"));
  }

  @Test
  @DisplayName("cadastro válido segue para o caso de uso e responde 200")
  void cadastroValidoResponde200() throws Exception {
    when(this.register.execute(any(RegisterHotelInput.class)))
      .thenReturn(new RegisterHotelOutput(HOTEL_ID, List.of(ROOM_ID)));

    this.registerHotel(VALID_REGISTER)
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true));

    verify(this.register).execute(any(RegisterHotelInput.class));
  }

  private String bookingWithDates(final String checkIn, final String checkOut) {
    return """
      {"customerId":"%s","hotelId":"%s","guests":2,"checkIn":"%s","checkOut":"%s",
       "rooms":[{"roomId":"%s","roomQuantity":1}]}
      """.formatted(CUSTOMER_ID, HOTEL_ID, checkIn, checkOut, ROOM_ID);
  }

  private String bookingWithRooms(final String rooms) {
    return """
      {"customerId":"%s","hotelId":"%s","guests":2,"checkIn":"10/01/2030","checkOut":"13/01/2030","rooms":%s}
      """.formatted(CUSTOMER_ID, HOTEL_ID, rooms);
  }

  private ResultActions booking(final String body) throws Exception {
    return this.mockMvc.perform(post("/hotel/booking").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private ResultActions registerHotel(final String body) throws Exception {
    return this.mockMvc.perform(post("/hotel").contentType(MediaType.APPLICATION_JSON).content(body));
  }
}
