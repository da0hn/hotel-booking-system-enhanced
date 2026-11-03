package com.hotel.booking.system.commons.application.dto.impl;

import com.hotel.booking.system.commons.application.dto.CollectionResponse;
import com.hotel.booking.system.commons.core.domain.pagination.Page;
import com.hotel.booking.system.commons.core.domain.pagination.PageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O envelope de página estende o de lista com o campo {@code page}. Estes testes olham o JSON,
 * porque o contrato é o que o cliente lê, e não os getters.
 */
@DisplayName("Envelope de página")
class PageResponseTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @SuppressWarnings("unchecked")
  private Map<String, Object> bodyOf(final Object response) {
    return this.json.readValue(this.json.writeValueAsString(response), Map.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> pageOf(final Map<String, Object> body) {
    return (Map<String, Object>) body.get("page");
  }

  @Test
  @DisplayName("contém success, data e page, e page informa number, size e hasNext")
  void paginaSemContagemTotalTemSuccessDataEPage() {
    final var page = Page.withoutTotal(List.of("a", "b"), PageRequest.of(0, 2), true);

    final var body = this.bodyOf(ResponseEntityAdapter.page(page).getBody());

    assertThat(body).containsOnlyKeys("success", "data", "page");
    assertThat(body.get("success")).isEqualTo(true);
    assertThat(body.get("data")).isEqualTo(List.of("a", "b"));
    assertThat(this.pageOf(body)).containsOnlyKeys("number", "size", "hasNext");
    assertThat(this.pageOf(body)).containsEntry("number", 0).containsEntry("size", 2).containsEntry("hasNext", true);
  }

  @Test
  @DisplayName("a página sem contagem total não contém totalElements nem totalPages")
  void paginaSemContagemTotalNaoTemTotais() {
    final var page = Page.withoutTotal(List.of("a"), PageRequest.of(3, 20), false);

    final var body = this.bodyOf(ResponseEntityAdapter.page(page).getBody());

    assertThat(this.pageOf(body)).doesNotContainKeys("totalElements", "totalPages");
    assertThat(this.pageOf(body)).containsEntry("number", 3).containsEntry("hasNext", false);
  }

  @Test
  @DisplayName("a página com contagem total informa totalElements e totalPages")
  void paginaComContagemTotalTemTotais() {
    final var page = Page.withTotal(List.of("a"), PageRequest.of(0, 20), 57L);

    final var body = this.bodyOf(ResponseEntityAdapter.page(page).getBody());

    assertThat(this.pageOf(body)).containsOnlyKeys("number", "size", "hasNext", "totalElements", "totalPages");
    assertThat(this.pageOf(body)).containsEntry("totalElements", 57).containsEntry("totalPages", 3);
    assertThat(this.pageOf(body)).containsEntry("hasNext", true);
  }

  @Test
  @DisplayName("a resposta de lista não contém page e o corpo existente não muda")
  void respostaDeListaNaoTemPage() {
    final var body = this.bodyOf(ResponseEntityAdapter.items(List.of("a")).getBody());

    assertThat(body).containsOnlyKeys("success", "data");
    assertThat(body.get("data")).isEqualTo(List.of("a"));
  }

  @Test
  @DisplayName("a página vazia responde 200 com data vazio, nunca 404")
  void paginaVaziaResponde200() {
    final var page = Page.withoutTotal(List.<String>of(), PageRequest.of(9, 20), false);

    final var response = ResponseEntityAdapter.page(page);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().getSuccess()).isTrue();
    assertThat(response.getBody().getData()).isEmpty();
    final var body = this.bodyOf(response.getBody());
    assertThat(body.get("data")).isEqualTo(List.of());
    assertThat(this.pageOf(body)).containsEntry("number", 9).containsEntry("hasNext", false);
  }

  @Test
  @DisplayName("é uma resposta de lista com page a mais, e o conteúdo é imutável")
  void estendeAListaEOConteudoEImutavel() {
    final var response = ResponseEntityAdapter.page(Page.withoutTotal(List.of("a"), PageRequest.of(0, 5), false));

    assertThat(response.getBody()).isInstanceOf(CollectionResponse.class);
    assertThat(response.getBody().getPage().number()).isZero();
    assertThatThrownBy(() -> response.getBody().getData().clear()).isInstanceOf(UnsupportedOperationException.class);
  }
}
