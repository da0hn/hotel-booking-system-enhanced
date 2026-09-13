package com.hotel.booking.system.commons.core.domain;

import com.hotel.booking.system.commons.core.domain.valueobject.FailureMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Lista de domínio")
class DomainListTest {

  @Test
  @DisplayName("copia a entrada e preserva ordem e duplicatas")
  void copiaEntradaEPreservaOrdemEDuplicatas() {
    final var original = new ArrayList<>(List.of("a", "b", "a"));
    final var messages = FailureMessages.newInstance(original);
    original.clear();
    assertThat(messages.size()).isEqualTo(3);
    assertThat(messages.isEmpty()).isFalse();
    assertThat(messages.isNotEmpty()).isTrue();
    assertThat(messages.contains("b")).isTrue();
    assertThat(messages.containsAll(List.of("a", "b"))).isTrue();
    assertThat(messages.get(1)).isEqualTo("b");
    assertThat(messages.indexOf("a")).isZero();
    assertThat(messages.lastIndexOf("a")).isEqualTo(2);
    assertThat(messages.toArray()).containsExactly("a", "b", "a");
    assertThat(messages.toArray(new String[0])).containsExactly("a", "b", "a");
    assertThat(messages.iterator()).toIterable().containsExactly("a", "b", "a");
    assertThat(messages.listIterator().next()).isEqualTo("a");
    assertThat(messages.listIterator(2).previous()).isEqualTo("b");
    assertThat(messages.subList(1, 3)).containsExactly("b", "a");
  }

  @Test
  @DisplayName("altera o conteúdo por meio das operações de List")
  void operacoesDeListaAlteramConteudoERetornamValoresAnteriores() {
    final var messages = FailureMessages.empty();
    assertThat(messages.isNotEmpty()).isFalse();
    assertThat(messages.add("a")).isTrue();
    messages.add(0, "b");
    assertThat(messages.addAll(List.of("c", "d"))).isTrue();
    assertThat(messages.addAll(1, List.of("e", "f"))).isTrue();
    assertThat(messages.data()).containsExactly("b", "e", "f", "a", "c", "d");
    assertThat(messages.set(0, "g")).isEqualTo("b");
    assertThat(messages.remove(1)).isEqualTo("e");
    assertThat(messages.remove("f")).isTrue();
    assertThat(messages.remove("inexistente")).isFalse();
    assertThat(messages.removeAll(List.of("g", "d"))).isTrue();
    assertThat(messages.retainAll(List.of("a"))).isTrue();
    assertThat(messages.data()).containsExactly("a");
    messages.clear();
    assertThat(messages.isEmpty()).isTrue();
  }

  @Test
  @DisplayName("compara tipo, ordem e conteúdo")
  void igualdadeRespeitaTipoOrdemEConteudo() {
    final var messages = FailureMessages.of("a", "b");
    assertThat(messages.equals(messages)).isTrue();
    assertThat(messages.equals(null)).isFalse();
    assertThat(messages.equals(List.of("a", "b"))).isFalse();
    assertThat(messages).isEqualTo(FailureMessages.of("a", "b"));
    assertThat(messages).isNotEqualTo(FailureMessages.of("b", "a"));
    assertThat(messages.hashCode()).isEqualTo(FailureMessages.of("a", "b").hashCode());
  }
}
