package com.hotel.booking.system.commons.core.domain.pagination;

/**
 * O pedido de uma página: qual ({@code number}, a partir de {@code 0}) e de que tamanho
 * ({@code size}, no mínimo {@code 1}).
 *
 * <p>O teto do tamanho não mora aqui. Ele é uma decisão de cada consulta (a busca de hotéis
 * aceita até 100), e quem a impõe é a fronteira HTTP, que responde {@code 400}. Este tipo
 * guarda só o que torna o pedido sem sentido: uma página negativa ou vazia não existe.</p>
 *
 * <p>É deliberadamente um tipo do domínio, e não o {@code Pageable} do Spring Data: o
 * {@code core} de nenhum serviço pode depender dele, e o adapter de persistência faz a
 * tradução.</p>
 */
public record PageRequest(int number, int size) {

  public PageRequest {
    if (number < 0) {
      throw new IllegalArgumentException("A page number must not be negative: " + number);
    }
    if (size < 1) {
      throw new IllegalArgumentException("A page size must be at least 1: " + size);
    }
  }

  public static PageRequest of(final int number, final int size) {
    return new PageRequest(number, size);
  }

  /**
   * Quantos elementos a consulta precisa pular para chegar a esta página. É {@code long} porque
   * o produto de dois {@code int} válidos já estoura o {@code int}.
   */
  public long offset() {
    return (long) this.number * this.size;
  }
}
