package com.hotel.booking.system.commons.core.domain.pagination;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Contrato de página do domínio")
class PageTest {

  @Test
  @DisplayName("a primeira página é a de número 0")
  void primeiraPaginaTemNumeroZero() {
    final var request = PageRequest.of(0, 20);

    assertThat(request.number()).isZero();
    assertThat(request.size()).isEqualTo(20);
    assertThat(request.offset()).isZero();
  }

  @Test
  @DisplayName("o deslocamento da página é número vezes tamanho, sem estourar o int")
  void deslocamentoMultiplicaNumeroPorTamanho() {
    assertThat(PageRequest.of(3, 20).offset()).isEqualTo(60L);
    assertThat(PageRequest.of(Integer.MAX_VALUE, 100).offset()).isEqualTo(Integer.MAX_VALUE * 100L);
  }

  @Test
  @DisplayName("recusa número de página negativo e tamanho menor que 1")
  void recusaNumeroNegativoETamanhoMenorQueUm() {
    assertThatThrownBy(() -> PageRequest.of(-1, 20)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PageRequest.of(0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PageRequest.of(0, -5)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a página sem contagem total não informa totalElements nem totalPages")
  void paginaSemContagemTotalNaoInformaTotais() {
    final var page = Page.withoutTotal(List.of("a", "b"), PageRequest.of(0, 2), true);

    assertThat(page.content()).containsExactly("a", "b");
    assertThat(page.number()).isZero();
    assertThat(page.size()).isEqualTo(2);
    assertThat(page.hasNext()).isTrue();
    assertThat(page.totalElements()).isEmpty();
    assertThat(page.totalPages()).isEmpty();
  }

  @Test
  @DisplayName("a página com contagem total informa totalElements e totalPages")
  void paginaComContagemTotalInformaTotais() {
    final var page = Page.withTotal(List.of("a", "b"), PageRequest.of(0, 2), 5L);

    assertThat(page.totalElements()).contains(5L);
    assertThat(page.totalPages()).contains(3);
  }

  @Test
  @DisplayName("com contagem total, hasNext só é verdadeiro se existe página seguinte")
  void comContagemTotalHasNextSoEVerdadeiroSeExistePaginaSeguinte() {
    assertThat(Page.withTotal(List.of(), PageRequest.of(0, 20), 57L).hasNext()).isTrue();
    assertThat(Page.withTotal(List.of(), PageRequest.of(1, 20), 57L).hasNext()).isTrue();
    assertThat(Page.withTotal(List.of(), PageRequest.of(2, 20), 57L).hasNext()).isFalse();
    assertThat(Page.withTotal(List.of(), PageRequest.of(2, 20), 60L).hasNext()).isFalse();
    assertThat(Page.withTotal(List.of(), PageRequest.of(1, 20), 60L).hasNext()).isTrue();
  }

  @Test
  @DisplayName("a página além do fim é vazia, não tem próxima e mantém o total")
  void paginaAlemDoFimNaoTemProxima() {
    final var page = Page.withTotal(List.of(), PageRequest.of(9, 20), 57L);

    assertThat(page.content()).isEmpty();
    assertThat(page.hasNext()).isFalse();
    assertThat(page.totalElements()).contains(57L);
    assertThat(page.totalPages()).contains(3);
  }

  @Test
  @DisplayName("sem nenhum elemento o total de páginas é zero")
  void semElementosOTotalDePaginasEZero() {
    final var page = Page.withTotal(List.of(), PageRequest.of(0, 20), 0L);

    assertThat(page.hasNext()).isFalse();
    assertThat(page.totalPages()).contains(0);
  }

  @Test
  @DisplayName("transforma o conteúdo e preserva a paginação")
  void mapTransformaOConteudoEPreservaAPaginacao() {
    final var page = Page.withoutTotal(List.of(1, 2), PageRequest.of(4, 2), true);

    final var mapped = page.map(number -> "n" + number);

    assertThat(mapped.content()).containsExactly("n1", "n2");
    assertThat(mapped.number()).isEqualTo(4);
    assertThat(mapped.size()).isEqualTo(2);
    assertThat(mapped.hasNext()).isTrue();
    assertThat(mapped.totalElements()).isEmpty();
  }

  @Test
  @DisplayName("map preserva a contagem total quando a página a tem")
  void mapPreservaAContagemTotal() {
    final var page = Page.withTotal(List.of(1), PageRequest.of(0, 1), 3L);

    final var mapped = page.map(String::valueOf);

    assertThat(mapped.totalElements()).contains(3L);
    assertThat(mapped.hasNext()).isTrue();
  }

  @Test
  @DisplayName("o conteúdo da página não muda quando a lista de origem muda")
  void conteudoEImutavel() {
    final var source = new ArrayList<>(List.of("a"));
    final var page = Page.withoutTotal(source, PageRequest.of(0, 5), false);

    source.add("b");

    assertThat(page.content()).containsExactly("a");
    assertThatThrownBy(() -> page.content().add("c")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("recusa um total negativo")
  void recusaTotalNegativo() {
    assertThatThrownBy(() -> Page.withTotal(List.of(), PageRequest.of(0, 20), -1L))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("-1");
  }

  @Test
  @DisplayName("recusa conteúdo, página pedida e total nulos")
  void recusaArgumentosNulos() {
    final var request = PageRequest.of(0, 20);

    assertThatThrownBy(() -> new Page<String>(null, request, false, Optional.empty()))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("content");
    assertThatThrownBy(() -> new Page<>(List.of(), null, false, Optional.empty()))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("request");
    assertThatThrownBy(() -> new Page<>(List.of(), request, false, null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("totalElements");
  }

  @Test
  @DisplayName("o total de páginas que não cabe em int lança, em vez de saturar em silêncio")
  void totalDePaginasQueNaoCabeEmIntLanca() {
    final var page = Page.withTotal(List.of(), PageRequest.of(0, 1), Long.MAX_VALUE);

    assertThatThrownBy(page::totalPages).isInstanceOf(ArithmeticException.class);
  }

  @Test
  @DisplayName("o total é um Optional vazio, e não nulo, quando não foi contado")
  void totalNaoContadoEOptionalVazio() {
    final var page = Page.withoutTotal(List.of(), PageRequest.of(0, 1), false);

    assertThat(page.totalElements()).isEqualTo(Optional.empty());
  }
}
