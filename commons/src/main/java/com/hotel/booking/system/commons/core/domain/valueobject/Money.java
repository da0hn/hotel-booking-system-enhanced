package com.hotel.booking.system.commons.core.domain.valueobject;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Valor monetário com escala fixa, imutável.
 *
 * <p>Guarda duas escalas distintas de propósito. A de <strong>cálculo</strong> tem 4 casas e é a
 * que toda instância carrega: a saga multiplica diária por quantidade e soma itens antes de o
 * valor chegar a qualquer olho humano, e arredondar cada parcela para 2 casas nesse meio do
 * caminho não dá o mesmo resultado que somar e arredondar no fim. A de
 * <strong>apresentação</strong> tem 2 casas e existe só na saída HTTP, via
 * {@link #getPresentationValue()}.</p>
 *
 * <p>A normalização acontece na construção, e é o que sustenta a igualdade: sem ela,
 * {@code Money.of(new BigDecimal("342.5"))} e {@code Money.of(new BigDecimal("342.50"))}
 * seriam o mesmo dinheiro em objetos que {@link BigDecimal#equals(Object)} considera
 * diferentes — o valor vem do banco com a escala da coluna e do JSON com a escala que o
 * emissor escreveu, então as duas formas circulam de verdade. O {@link #equals(Object)}
 * compara por {@link BigDecimal#compareTo(BigDecimal)} mesmo assim, para que a corretude não
 * dependa de a normalização nunca ser esquecida em um caminho novo.</p>
 *
 * <p>O arredondamento é {@link RoundingMode#HALF_UP} e é explícito: um valor com mais de 4
 * casas é arredondado ao virar {@code Money}, não silenciosamente truncado na gravação como
 * acontecia quando as colunas eram {@code DECIMAL(10,0)}.</p>
 */
public class Money {

  /**
   * Declaradas antes de {@link #ZERO} porque ele as usa na própria inicialização estática.
   * {@code CALCULATION_SCALE} sobreviveria à inversão por ser constante de compilação, mas
   * {@code ROUNDING_MODE} é uma referência: fora de ordem, {@code ZERO} nasceria com ela nula.
   */
  private static final int CALCULATION_SCALE = 4;
  private static final int PRESENTATION_SCALE = 2;
  private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

  public static final Money ZERO = Money.of(BigDecimal.ZERO);

  private final BigDecimal value;

  private Money(final BigDecimal value) {
    this.value = value.setScale(CALCULATION_SCALE, ROUNDING_MODE);
  }

  public static Money of(final BigDecimal value) {
    return new Money(value);
  }

  public static Money of(final Integer value) {
    return new Money(BigDecimal.valueOf(value));
  }

  public static Money of(final Double value) {
    return new Money(BigDecimal.valueOf(value));
  }

  /**
   * O valor de cálculo, sempre com {@value #CALCULATION_SCALE} casas. É o que vai para o banco
   * e para o contrato de eventos. Para exibir ao usuário, use {@link #getPresentationValue()}.
   */
  public BigDecimal getValue() {
    return this.value;
  }

  /**
   * O valor reduzido a {@value #PRESENTATION_SCALE} casas, para as respostas HTTP. É o único
   * ponto em que a precisão de cálculo é descartada, e ele fica nas bordas: nada que ainda vá
   * ser somado ou multiplicado deve passar por aqui.
   */
  public BigDecimal getPresentationValue() {
    return this.value.setScale(PRESENTATION_SCALE, ROUNDING_MODE);
  }

  public boolean isNegative() {
    return this.value.compareTo(BigDecimal.ZERO) < 0;
  }

  public boolean isZero() {
    return this.value.compareTo(BigDecimal.ZERO) == 0;
  }

  public Money multiply(final BigDecimal value) {
    return new Money(this.value.multiply(value));
  }

  public Money add(final Money money) {
    return new Money(this.value.add(money.value));
  }

  public boolean isNotEqual(final Money money) {
    return !this.equals(money);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || this.getClass() != o.getClass()) return false;

    final Money money = (Money) o;

    return this.value.compareTo(money.value) == 0;
  }

  @Override
  public int hashCode() {
    return this.value.stripTrailingZeros().hashCode();
  }
}
