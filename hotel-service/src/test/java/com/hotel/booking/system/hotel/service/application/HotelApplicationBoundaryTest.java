package com.hotel.booking.system.hotel.service.application;

import com.hotel.booking.system.hotel.service.application.service.impl.HotelApplicationServiceImpl;
import com.hotel.booking.system.hotel.service.application.web.controller.HotelController;
import com.hotel.booking.system.hotel.service.core.application.dto.*;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Fronteira HTTP do serviço de hotéis")
class HotelApplicationBoundaryTest {
  private final RegisterHotelUseCase register = mock(RegisterHotelUseCase.class);
  private final SearchHotelAvailableUseCase search = mock(SearchHotelAvailableUseCase.class);
  private final BookingRoomRequestUseCase book = mock(BookingRoomRequestUseCase.class);
  private final HotelController controller = new HotelController(new HotelApplicationServiceImpl(this.register, this.search, this.book));

  @Test
  @DisplayName("declara transações na fronteira de cada operação")
  void declaraTransacoesNaFronteiraDeCadaOperacao() throws NoSuchMethodException {
    final var register = HotelApplicationServiceImpl.class
      .getMethod("register", RegisterHotelInput.class)
      .getAnnotation(Transactional.class);
    final var search = HotelApplicationServiceImpl.class
      .getMethod("searchHotelAvailableBy", SearchHotelAvailableInput.class)
      .getAnnotation(Transactional.class);
    final var booking = HotelApplicationServiceImpl.class
      .getMethod("bookingRoomRequest", BookingRoomInput.class)
      .getAnnotation(Transactional.class);

    assertThat(register).isNotNull().extracting(Transactional::readOnly).isEqualTo(false);
    assertThat(search).isNotNull().extracting(Transactional::readOnly).isEqualTo(true);
    assertThat(booking).isNotNull().extracting(Transactional::readOnly).isEqualTo(false);
  }

  @Test
  @DisplayName("retorna o hotel registrado dentro do envelope HTTP")
  void cadastroRetornaEnvelopeComIdentificadoresCriados() {
    final var input = RegisterHotelInput.builder().name("Hotel").build();
    final var output = new RegisterHotelOutput("hotel-id", List.of("room-id"));
    when(this.register.execute(input)).thenReturn(output);
    final var response = this.controller.register(input);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody().getSuccess()).isTrue();
    assertThat(response.getBody().getData()).isSameAs(output);
    verify(this.register).execute(input);
    verifyNoInteractions(this.search, this.book);
  }

  @Test
  @DisplayName("mantém os filtros da busca e devolve o envelope HTTP")
  void buscaMantemCorrespondenciaEntreParametrosEEnvelope() {
    final var input = new SearchHotelAvailableInput("Cuiaba", "MT", "Pousada", "Portal");
    final var output = List.of(SearchHotelAvailableOutput.builder().name("Portal").build());
    when(this.search.execute(input)).thenReturn(output);
    final var response = this.controller.searchHotelAvailableBy("Portal", "Cuiaba", "MT", "Pousada");
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody().getSuccess()).isTrue();
    assertThat(response.getBody().getData()).containsExactlyElementsOf(output);
    verify(this.search).execute(input);
    verifyNoInteractions(this.register, this.book);
  }

  @Test
  @DisplayName("retorna o identificador assíncrono da reserva")
  void reservaRetornaIdentificadorAssincronoSemEsperarConfirmacao() {
    final var input = BookingRoomInput.builder().customerId("customer").build();
    final var output = new BookingRoomOutput(UUID.randomUUID());
    when(this.book.execute(input)).thenReturn(output);
    final var response = this.controller.bookingRoom(input);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody().getSuccess()).isTrue();
    assertThat(response.getBody().getData()).isSameAs(output);
    verify(this.book).execute(input);
    verifyNoInteractions(this.register, this.search);
  }

  @Test
  @DisplayName("encaminha os argumentos ao iniciar o Spring Boot")
  void entrypointForwardsArgumentsToSpringBoot() {
    assertThat(new com.hotel.booking.system.hotel.service.HotelServiceApplication()).isNotNull();
    try (final var spring = mockStatic(SpringApplication.class)) {
      final var args = new String[]{"--spring.profiles.active=test"};
      com.hotel.booking.system.hotel.service.HotelServiceApplication.main(args);
      spring.verify(() -> SpringApplication.run(com.hotel.booking.system.hotel.service.HotelServiceApplication.class, args));
    }
  }
}
