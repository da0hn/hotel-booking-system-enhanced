package com.hotel.booking.system.commons.core.domain.valueobject;

import com.hotel.booking.system.commons.core.domain.exception.InvalidIdException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Formato de UUID dos ids recebidos na fronteira HTTP")
class UuidFormatTest {

  @Test
  @DisplayName("aceita um UUID bem formado")
  void aceitaUuidValido() {
    assertThatCode(() -> UuidFormat.requireValid("customerId", UUID.randomUUID().toString()))
      .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("aceita o que o UUID.fromString do JDK aceita, porque é ele que converte o id adiante")
  void aceitaOQueOJdkAceita() {
    assertThatCode(() -> UuidFormat.requireValid("customerId", "1-1-1-1-1")).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "recusa \"{0}\"")
  @NullAndEmptySource
  @ValueSource(strings = {" ", "abc", "xyz", "not-a-uuid", "f1e28a47-8852-45e8-b8e1-e6701633dd5g", "1-1-1-1-1-1"})
  @DisplayName("recusa o valor que não é um UUID, inclusive nulo e vazio")
  void recusaValorQueNaoEUuid(final String value) {
    assertThatThrownBy(() -> UuidFormat.requireValid("customerId", value))
      .isInstanceOf(InvalidIdException.class);
  }

  @Test
  @DisplayName("nomeia o campo recusado e usa a chave request.id.invalid como mensagem")
  void nomeiaOCampoRecusado() {
    assertThatThrownBy(() -> UuidFormat.requireValid("reservationOrderId", "abc"))
      .isInstanceOfSatisfying(InvalidIdException.class, exception -> {
        assertThat(exception.getField()).isEqualTo("reservationOrderId");
        assertThat(exception).hasMessage("request.id.invalid");
      });
  }

  @Test
  @DisplayName("não carrega o valor enviado pelo cliente na exceção")
  void naoCarregaOValorDoCliente() {
    assertThatThrownBy(() -> UuidFormat.requireValid("customerId", "<script>alert(1)</script>"))
      .isInstanceOfSatisfying(InvalidIdException.class, exception ->
        assertThat(exception.getMessage() + exception.getField()).doesNotContain("script"));
  }
}
