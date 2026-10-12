package com.hotel.booking.system.hotel.service.application.web.controller;

import com.hotel.booking.system.hotel.service.application.service.impl.HotelApplicationServiceImpl;
import com.hotel.booking.system.hotel.service.core.application.dto.SearchHotelAvailableInput;
import com.hotel.booking.system.hotel.service.core.application.dto.SearchHotelAvailableOutput;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.BookingRoomRequestUseCase;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.RegisterHotelUseCase;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.SearchHotelAvailableUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP da busca {@code GET /hotel}: busca é consulta, então zero resultados é resposta
 * válida (200 com {@code data} vazio), e 404 fica para o acesso a um recurso identificado que não
 * existe. A issue #11 nasceu de a busca responder 404 quando nenhum hotel atendia ao filtro.
 */
@DisplayName("Busca de hotéis na fronteira HTTP")
class HotelControllerSearchTest {

  private final SearchHotelAvailableUseCase search = mock(SearchHotelAvailableUseCase.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    final var service = new HotelApplicationServiceImpl(
      mock(RegisterHotelUseCase.class),
      this.search,
      mock(BookingRoomRequestUseCase.class)
    );
    this.mockMvc = MockMvcBuilders.standaloneSetup(new HotelController(service)).build();
  }

  @Test
  @DisplayName("responde 200 com data vazio quando nenhum hotel atende ao filtro")
  void buscaSemResultadoResponde200ComColecaoVazia() throws Exception {
    when(this.search.execute(any(SearchHotelAvailableInput.class))).thenReturn(List.of());

    this.mockMvc.perform(get("/hotel").param("city", "CidadeInexistente"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true))
      .andExpect(jsonPath("$.data").isArray())
      .andExpect(jsonPath("$.data").isEmpty());
  }

  @Test
  @DisplayName("responde 200 com data vazio na busca sem filtros quando não há hotel cadastrado")
  void buscaSemFiltrosEmBaseVaziaResponde200() throws Exception {
    when(this.search.execute(any(SearchHotelAvailableInput.class))).thenReturn(List.of());

    this.mockMvc.perform(get("/hotel"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true))
      .andExpect(jsonPath("$.data").isEmpty());

    verify(this.search).execute(new SearchHotelAvailableInput("", "", "", ""));
  }

  @Test
  @DisplayName("mantém 200 com os hotéis no corpo quando a busca encontra resultado")
  void buscaComResultadoMantem200ComOsItens() throws Exception {
    final var portal = SearchHotelAvailableOutput.builder().id("hotel-id").name("Portal").city("Cuiaba").build();
    when(this.search.execute(any(SearchHotelAvailableInput.class))).thenReturn(List.of(portal));

    this.mockMvc.perform(get("/hotel").param("city", "Cuiaba"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true))
      .andExpect(jsonPath("$.data.length()").value(1))
      .andExpect(jsonPath("$.data[0].name").value("Portal"))
      .andExpect(jsonPath("$.data[0].city").value("Cuiaba"));
  }
}
