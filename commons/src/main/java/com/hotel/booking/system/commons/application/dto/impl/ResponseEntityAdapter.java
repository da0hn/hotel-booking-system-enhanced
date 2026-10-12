package com.hotel.booking.system.commons.application.dto.impl;


import com.hotel.booking.system.commons.application.dto.CollectionResponse;
import com.hotel.booking.system.commons.application.dto.Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collection;
import java.util.Optional;

public class ResponseEntityAdapter<T> extends ResponseEntity<T> {

  private ResponseEntityAdapter(
    final T body,
    final HttpStatus status
  ) {
    super(body, status);
  }

  /**
   * Resposta de uma busca: sempre {@code 200}, inclusive com a coleção vazia. Zero resultados é um
   * resultado válido de uma busca que deu certo, e não a ausência de um recurso; clientes HTTP
   * tratam {@code 404} como erro, o que quebraria o consumidor de qualquer listagem com filtro.
   * Para o acesso a um recurso identificado que pode não existir, use {@link #orNotFound}.
   */
  public static <T> ResponseEntity<CollectionResponse<T>> items(final Collection<T> item) {
    return new ResponseEntityAdapter<>(ApiCollectionResponse.of(item), HttpStatus.OK);
  }

  /**
   * Resposta de acesso a um recurso identificado (por exemplo {@code GET /recurso/{id}}): {@code 200}
   * quando ele existe e {@code 404} quando não existe. É o único caso em que {@code 404} é a resposta
   * certa. O corpo do {@code 404} é um {@link ApiErrorResponse} com a chave de mensagem de quem chama,
   * o mesmo formato das demais respostas de erro.
   */
  public static <T> ResponseEntity<Response<T>> orNotFound(
    final Optional<T> data,
    final String message
  ) {
    return data
      .map(ResponseEntityAdapter::of)
      .orElseGet(() -> new ResponseEntityAdapter<>(ApiErrorResponse.of(null, message), HttpStatus.NOT_FOUND));
  }

  public static <T> ResponseEntity<Response<T>> of(final T data) {
    return new ResponseEntityAdapter<>(ApiResponse.of(data), HttpStatus.OK);
  }

  public static <T> ResponseEntity<Response<T>> of(
    final T data,
    final HttpStatus status
  ) {
    return new ResponseEntityAdapter<>(ApiResponse.of(data), status);
  }


  public static ResponseEntity<Response<Void>> empty() {
    return new ResponseEntityAdapter<>(ApiResponse.of(null), HttpStatus.OK);
  }

}
