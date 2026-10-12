package com.hotel.booking.system.commons.application.dto.impl;


import com.hotel.booking.system.commons.application.dto.CollectionResponse;
import com.hotel.booking.system.commons.application.dto.Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collection;

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
   */
  public static <T> ResponseEntity<CollectionResponse<T>> items(final Collection<T> item) {
    return new ResponseEntityAdapter<>(ApiCollectionResponse.of(item), HttpStatus.OK);
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
