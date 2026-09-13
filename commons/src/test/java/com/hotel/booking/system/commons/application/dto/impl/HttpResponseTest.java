package com.hotel.booking.system.commons.application.dto.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Respostas HTTP")
class HttpResponseTest {

  @Test
  @DisplayName("preserva o payload e o status HTTP da resposta simples")
  void respostaSimplesPreservaPayloadEStatusHttp() {
    final var success = ResponseEntityAdapter.of("reserva");
    assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(success.getBody().getData()).isEqualTo("reserva");
    assertThat(success.getBody().getSuccess()).isTrue();
    final var created = ResponseEntityAdapter.of("hotel", HttpStatus.CREATED);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody().getData()).isEqualTo("hotel");
    final var empty = ResponseEntityAdapter.empty();
    assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(empty.getBody().getData()).isNull();
    assertThat(empty.getBody().getSuccess()).isTrue();
  }

  @Test
  @DisplayName("retorna 404 para coleção vazia e torna a coleção encontrada imutável")
  void colecaoVaziaResponde404EColecaoPreenchidaNaoPermiteAlteracao() {
    final var empty = ResponseEntityAdapter.items(List.of());
    assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(empty.getBody().getData()).isEmpty();
    final var found = ResponseEntityAdapter.items(List.of("hotel"));
    assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(found.getBody().getSuccess()).isTrue();
    assertThat(found.getBody().getData()).containsExactly("hotel");
    assertThatThrownBy(() -> found.getBody().getData().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThat(ApiCollectionResponse.of(null).getData()).isEmpty();
    assertThat(new ApiCollectionResponse<>(List.of(), false).getSuccess()).isFalse();
    assertThat(new ApiResponse<>("erro", false).getSuccess()).isFalse();
  }

  @Test
  @DisplayName("inclui mensagem, detalhe e instante na resposta de erro")
  void respostaDeErroIncluiMensagemDetalheEInstanteDaFalha() {
    final var before = LocalDateTime.now();
    final var detail = new ApiErrorResponse.ApiErrorDetail("quarto inexistente");
    final var error = ApiErrorResponse.of(detail, "reserva recusada");
    assertThat(error.getData()).isSameAs(detail);
    assertThat(detail.detail()).isEqualTo("quarto inexistente");
    assertThat(error.getMessage()).isEqualTo("reserva recusada");
    assertThat(error.getSuccess()).isFalse();
    assertThat(error.getTime()).isBetween(before, LocalDateTime.now());
    final var noDetail = ApiErrorResponse.of("falha");
    assertThat(noDetail.getData()).isNull();
    assertThat(noDetail.getMessage()).isEqualTo("falha");
  }
}
