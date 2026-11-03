package com.hotel.booking.system.commons.application.web;

import org.springframework.beans.propertyeditors.CustomNumberEditor;

/**
 * Converte texto em {@code Integer} como o Spring faz por padrão, exceto o texto vazio, que vira
 * {@link #EMPTY_PARAMETER} em vez de {@code null}.
 *
 * <p>O Spring converte a string vazia em {@code null} para um {@code Integer}, e a validação não
 * avalia {@code null}: um {@code ?page=} valeria como parâmetro ausente. O valor que este editor
 * devolve no lugar é um que nenhuma restrição de parâmetro aceita, então o {@code @Min} do próprio
 * parâmetro o recusa com a chave que ele declara, e a violação sai junto com as dos outros.</p>
 */
final class EmptyIsInvalidIntegerEditor extends CustomNumberEditor {

  /**
   * O que um parâmetro presente e vazio vira antes da validação. Não tem outra função: uma
   * restrição que o aceitasse deixaria o vazio passar por um valor legítimo.
   */
  static final int EMPTY_PARAMETER = Integer.MIN_VALUE;

  EmptyIsInvalidIntegerEditor() {
    super(Integer.class, false);
  }

  @Override
  public void setAsText(final String text) {
    if (text.isEmpty()) {
      this.setValue(EMPTY_PARAMETER);
      return;
    }
    super.setAsText(text);
  }
}
