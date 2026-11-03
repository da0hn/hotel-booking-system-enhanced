package com.hotel.booking.system.commons.core.domain.pagination;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Uma página de resultados: o conteúdo, qual página ela é e se existe uma seguinte.
 *
 * <p>Há duas formas, e a diferença é o custo. A <strong>sem contagem total</strong>
 * ({@link #withoutTotal}) é a padrão: a consulta pede um elemento a mais do que o tamanho da
 * página, e a sobra diz se há próxima, sem um {@code COUNT}. A <strong>com contagem total</strong>
 * ({@link #withTotal}) sabe quantos elementos existem e quantas páginas isso dá, ao preço de uma
 * consulta extra por requisição; {@code hasNext} sai da conta. Quando o total não foi contado,
 * {@link #totalElements()} e {@link #totalPages()} são vazios, e não zero: zero é uma resposta
 * ("não há nada"), vazio é a ausência dela.</p>
 *
 * <p>Não depende do Spring Data. Os adapters convertem de {@code Slice}/{@code Page} do Spring
 * para este tipo na fronteira da persistência.</p>
 *
 * @param content       os elementos desta página, em ordem; a lista é imutável
 * @param request       a página que foi pedida
 * @param hasNext       {@code true} somente se existe página seguinte
 * @param totalElements o total de elementos da consulta, quando foi contado
 */
public record Page<T>(
  List<T> content,
  PageRequest request,
  boolean hasNext,
  Optional<Long> totalElements
) {

  public Page {
    content = List.copyOf(Objects.requireNonNull(content, "content"));
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(totalElements, "totalElements");
  }

  /**
   * Página cuja consulta não contou o total. O {@code hasNext} vem de quem consultou, que sabe se
   * o elemento extra existiu.
   */
  public static <T> Page<T> withoutTotal(
    final List<T> content,
    final PageRequest request,
    final boolean hasNext
  ) {
    return new Page<>(content, request, hasNext, Optional.empty());
  }

  /**
   * Página cuja consulta contou o total. {@code hasNext} é derivado: existe próxima quando o fim
   * desta página ainda não alcança o total. Uma página além do fim é vazia e não tem próxima.
   */
  public static <T> Page<T> withTotal(
    final List<T> content,
    final PageRequest request,
    final long totalElements
  ) {
    if (totalElements < 0) {
      throw new IllegalArgumentException("A total must not be negative: " + totalElements);
    }
    final var hasNext = request.offset() + request.size() < totalElements;
    return new Page<>(content, request, hasNext, Optional.of(totalElements));
  }

  public int number() {
    return this.request.number();
  }

  public int size() {
    return this.request.size();
  }

  /**
   * Quantas páginas o total comporta; vazio quando o total não foi contado.
   */
  public Optional<Integer> totalPages() {
    return this.totalElements.map(total -> Math.toIntExact(Math.ceilDiv(total, this.size())));
  }

  /**
   * Transforma o conteúdo e preserva tudo o mais: a página pedida, {@code hasNext} e o total.
   */
  public <R> Page<R> map(final Function<? super T, ? extends R> mapper) {
    final List<R> mapped = this.content.stream().<R>map(mapper).toList();
    return new Page<>(mapped, this.request, this.hasNext, this.totalElements);
  }
}
