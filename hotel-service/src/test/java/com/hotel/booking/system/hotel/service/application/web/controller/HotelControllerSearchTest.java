package com.hotel.booking.system.hotel.service.application.web.controller;

import com.hotel.booking.system.commons.core.domain.pagination.Page;
import com.hotel.booking.system.commons.core.domain.pagination.PageRequest;
import com.hotel.booking.system.hotel.service.application.service.impl.HotelApplicationServiceImpl;
import com.hotel.booking.system.hotel.service.application.web.advice.HotelExceptionHandler;
import com.hotel.booking.system.hotel.service.core.application.dto.SearchHotelAvailableInput;
import com.hotel.booking.system.hotel.service.core.application.dto.SearchHotelAvailableOutput;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.BookingRoomRequestUseCase;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.RegisterHotelUseCase;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.SearchHotelAvailableUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    this.mockMvc = MockMvcBuilders.standaloneSetup(new HotelController(service))
      .setControllerAdvice(new HotelExceptionHandler())
      .build();
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

  @Test
  @DisplayName("responde uma lista, sem page, quando a requisição não traz page nem size")
  void semPageNemSizeRespondeListaSemPage() throws Exception {
    final var portal = SearchHotelAvailableOutput.builder().id("hotel-id").name("Portal").build();
    when(this.search.execute(any(SearchHotelAvailableInput.class))).thenReturn(List.of(portal));

    this.mockMvc.perform(get("/hotel"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.length()").value(1))
      .andExpect(jsonPath("$.page").doesNotExist());

    verify(this.search, never()).execute(any(SearchHotelAvailableInput.class), any(PageRequest.class));
  }

  @Test
  @DisplayName("responde uma página sem contagem total quando a requisição traz page e size")
  void comPageESizeRespondePaginaSemContagemTotal() throws Exception {
    final var portal = SearchHotelAvailableOutput.builder().id("hotel-id").name("Portal").build();
    final var request = PageRequest.of(1, 5);
    when(this.search.execute(any(SearchHotelAvailableInput.class), eq(request)))
      .thenReturn(Page.withoutTotal(List.of(portal), request, true));

    this.mockMvc.perform(get("/hotel").param("page", "1").param("size", "5"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true))
      .andExpect(jsonPath("$.data.length()").value(1))
      .andExpect(jsonPath("$.data[0].name").value("Portal"))
      .andExpect(jsonPath("$.page.number").value(1))
      .andExpect(jsonPath("$.page.size").value(5))
      .andExpect(jsonPath("$.page.hasNext").value(true))
      .andExpect(jsonPath("$.page.totalElements").doesNotExist())
      .andExpect(jsonPath("$.page.totalPages").doesNotExist());

    verify(this.search, never()).execute(any(SearchHotelAvailableInput.class));
  }

  @Test
  @DisplayName("assume page 0 quando a requisição traz só size, e size 20 quando traz só page")
  void umSoParametroFazOOutroAssumirOPadrao() throws Exception {
    when(this.search.execute(any(SearchHotelAvailableInput.class), any(PageRequest.class)))
      .thenAnswer(invocation -> Page.withoutTotal(List.of(), invocation.getArgument(1), false));

    this.mockMvc.perform(get("/hotel").param("size", "10"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.page.number").value(0))
      .andExpect(jsonPath("$.page.size").value(10));
    this.mockMvc.perform(get("/hotel").param("page", "2"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.page.number").value(2))
      .andExpect(jsonPath("$.page.size").value(20));

    verify(this.search).execute(any(SearchHotelAvailableInput.class), eq(PageRequest.of(0, 10)));
    verify(this.search).execute(any(SearchHotelAvailableInput.class), eq(PageRequest.of(2, 20)));
  }

  @Test
  @DisplayName("repassa à página os mesmos filtros da busca em lista")
  void paginaPreservaOsFiltros() throws Exception {
    when(this.search.execute(any(SearchHotelAvailableInput.class), any(PageRequest.class)))
      .thenAnswer(invocation -> Page.withoutTotal(List.of(), invocation.getArgument(1), false));

    this.mockMvc.perform(
        get("/hotel")
          .param("name", "Portal")
          .param("city", "Cuiaba")
          .param("state", "MT")
          .param("category", "Pousada")
          .param("page", "0")
      )
      .andExpect(status().isOk());

    verify(this.search).execute(new SearchHotelAvailableInput("Cuiaba", "MT", "Pousada", "Portal"), PageRequest.of(0, 20));
  }

  @Test
  @DisplayName("responde 200 com data vazio, e não 404, para a página além do fim")
  void paginaAlemDoFimResponde200ComDataVazio() throws Exception {
    final var request = PageRequest.of(9, 20);
    when(this.search.execute(any(SearchHotelAvailableInput.class), eq(request)))
      .thenReturn(Page.withoutTotal(List.<SearchHotelAvailableOutput>of(), request, false));

    this.mockMvc.perform(get("/hotel").param("page", "9"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true))
      .andExpect(jsonPath("$.data").isArray())
      .andExpect(jsonPath("$.data").isEmpty())
      .andExpect(jsonPath("$.page.number").value(9))
      .andExpect(jsonPath("$.page.hasNext").value(false));
  }

  @Test
  @DisplayName("aceita os limites da faixa: page 0, size 1 e size 100")
  void aceitaOsLimitesDaFaixa() throws Exception {
    when(this.search.execute(any(SearchHotelAvailableInput.class), any(PageRequest.class)))
      .thenAnswer(invocation -> Page.withoutTotal(List.of(), invocation.getArgument(1), false));

    this.mockMvc.perform(get("/hotel").param("page", "0").param("size", "1")).andExpect(status().isOk());
    this.mockMvc.perform(get("/hotel").param("size", "100")).andExpect(status().isOk());
  }

  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({"page,-1,page.number.invalid", "size,0,page.size.invalid", "size,-5,page.size.invalid", "size,101,page.size.invalid"})
  @DisplayName("responde 400 no formato de erro do sistema quando page ou size está fora da faixa")
  void pageOuSizeForaDaFaixaResponde400(
    final String parameter,
    final String value,
    final String message
  ) throws Exception {
    this.mockMvc.perform(get("/hotel").param(parameter, value))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("request.invalid"))
      .andExpect(jsonPath("$.data.length()").value(1))
      .andExpect(jsonPath("$.data[0].field").value(parameter))
      .andExpect(jsonPath("$.data[0].message").value(message))
      .andExpect(jsonPath("$.time").isString());

    verifyNoInteractions(this.search);
  }

  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({"page,abc", "size,abc", "page,1.5", "size,99999999999"})
  @DisplayName("responde 400 no formato de erro do sistema quando page ou size não é um inteiro")
  void pageOuSizeNaoNumericoResponde400(
    final String parameter,
    final String value
  ) throws Exception {
    this.mockMvc.perform(get("/hotel").param(parameter, value))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("request.invalid"));

    verifyNoInteractions(this.search);
  }

  @ParameterizedTest(name = "{0}= (vazio)")
  @CsvSource({"page,page.number.invalid", "size,page.size.invalid"})
  @DisplayName("responde 400 com a chave da restrição quando page ou size vem presente e vazio")
  void pageOuSizeVazioResponde400(
    final String parameter,
    final String message
  ) throws Exception {
    this.mockMvc.perform(get("/hotel").param(parameter, ""))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("request.invalid"))
      .andExpect(jsonPath("$.data.length()").value(1))
      .andExpect(jsonPath("$.data[0].field").value(parameter))
      .andExpect(jsonPath("$.data[0].message").value(message))
      .andExpect(jsonPath("$.time").isString());

    verifyNoInteractions(this.search);
  }

  @Test
  @DisplayName("lista os dois parâmetros quando page e size vêm vazios juntos")
  void pageESizeVaziosJuntosListamOsDois() throws Exception {
    this.mockMvc.perform(get("/hotel").param("page", "").param("size", ""))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.data.length()").value(2))
      .andExpect(jsonPath("$.data[0].field").value("page"))
      .andExpect(jsonPath("$.data[0].message").value("page.number.invalid"))
      .andExpect(jsonPath("$.data[1].field").value("size"))
      .andExpect(jsonPath("$.data[1].message").value("page.size.invalid"));

    verifyNoInteractions(this.search);
  }

  @Test
  @DisplayName("recusa só o parâmetro vazio quando o outro é válido")
  void recusaApenasOParametroVazio() throws Exception {
    this.mockMvc.perform(get("/hotel").param("page", "2").param("size", ""))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.data.length()").value(1))
      .andExpect(jsonPath("$.data[0].field").value("size"));

    verifyNoInteractions(this.search);
  }

  @Test
  @DisplayName("os filtros de texto vazios continuam contando como ausentes")
  void filtrosDeTextoVaziosContamComoAusentes() throws Exception {
    when(this.search.execute(any(SearchHotelAvailableInput.class))).thenReturn(List.of());

    this.mockMvc.perform(get("/hotel").param("name", "").param("city", "").param("state", "").param("category", ""))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.page").doesNotExist());

    verify(this.search, never()).execute(any(SearchHotelAvailableInput.class), any(PageRequest.class));
  }

  @Test
  @DisplayName("lista os dois parâmetros quando page e size são inválidos juntos")
  void pageESizeInvalidosJuntosListamOsDois() throws Exception {
    this.mockMvc.perform(get("/hotel").param("page", "-1").param("size", "0"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.data.length()").value(2))
      .andExpect(jsonPath("$.data[0].field").value("page"))
      .andExpect(jsonPath("$.data[1].field").value("size"));
  }
}
