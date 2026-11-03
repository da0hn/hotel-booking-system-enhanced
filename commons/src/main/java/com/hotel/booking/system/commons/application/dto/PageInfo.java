package com.hotel.booking.system.commons.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hotel.booking.system.commons.core.domain.pagination.Page;

import java.util.Optional;

/**
 * O campo {@code page} do envelope de página: onde o cliente está e se há mais.
 *
 * <p>{@code totalElements} e {@code totalPages} só existem quando a consulta contou o total. O
 * {@code NON_ABSENT} os tira do JSON quando vazios, de modo que a página sem contagem não leva
 * nem o campo nem um {@code null}: o cliente que vê a chave pode confiar no valor.</p>
 *
 * @param number a página, a partir de {@code 0}
 * @param size   o tamanho pedido da página
 * @param hasNext {@code true} somente se existe página seguinte
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record PageInfo(
  int number,
  int size,
  boolean hasNext,
  Optional<Long> totalElements,
  Optional<Integer> totalPages
) {

  public static PageInfo of(final Page<?> page) {
    return new PageInfo(
      page.number(),
      page.size(),
      page.hasNext(),
      page.totalElements(),
      page.totalPages()
    );
  }
}
