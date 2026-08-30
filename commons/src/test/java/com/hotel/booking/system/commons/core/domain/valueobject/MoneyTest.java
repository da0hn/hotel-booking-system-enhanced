package com.hotel.booking.system.commons.core.domain.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trava as duas propriedades que a issue #1 expôs e que nada mais no projeto guarda.
 * <p>
 * A primeira é a preservação dos centavos. Ela nasceu como problema de schema — as colunas
 * eram {@code decimal} sem escala, e o MySQL as tratava como {@code DECIMAL(10,0)} —, mas
 * corrigir o banco não bastava: o arredondamento também pode acontecer no meio do cálculo,
 * antes de o valor chegar a qualquer coluna. É o que a escala 4 do {@code Money} impede, e o
 * que os testes de soma abaixo cobram.
 * <p>
 * A segunda é a igualdade. {@code isNotEqual} sempre comparou por {@code compareTo} enquanto
 * {@code equals} usava {@code BigDecimal#equals}, sensível a escala. Isso passou anos
 * despercebido porque todos os valores eram inteiros vindos de uma coluna de escala zero: as
 * duas formas de comparar davam o mesmo resultado por acidente do dado, não por acordo entre
 * os métodos. Com centavos no banco, o acidente acabou.
 */
@DisplayName("Money")
class MoneyTest {

  @Nested
  @DisplayName("preserva centavos")
  class PreservaCentavos {

    @Test
    @DisplayName("ao somar dois itens com centavos")
    void aoSomarDoisItensComCentavos() {
      final var total = Money.of(new BigDecimal("199.99")).add(Money.of(new BigDecimal("199.99")));

      assertThat(total.getValue()).isEqualByComparingTo("399.98");
    }

    @Test
    @DisplayName("ao multiplicar a diária pela quantidade de quartos")
    void aoMultiplicarADiariaPelaQuantidadeDeQuartos() {
      final var total = Money.of(new BigDecimal("199.99")).multiply(new BigDecimal(3));

      assertThat(total.getValue()).isEqualByComparingTo("599.97");
    }

    /**
     * O caso que justifica a escala de cálculo ser maior que a de apresentação. Três parcelas
     * de {@code 0.005} somam {@code 0.015}, que exibido dá {@code 0.02}. Se cada parcela fosse
     * reduzida a duas casas na origem, cada uma viraria {@code 0.01} e o total exibido seria
     * {@code 0.03} — um centavo inventado por arredondar cedo demais.
     */
    @Test
    @DisplayName("somando antes de arredondar, não depois")
    void somandoAntesDeArredondarNaoDepois() {
      final var parcela = Money.of(new BigDecimal("0.005"));

      final var total = parcela.add(parcela).add(parcela);

      assertThat(total.getPresentationValue()).isEqualByComparingTo("0.02");
      assertThat(parcela.getPresentationValue().multiply(new BigDecimal(3)))
        .as("o mesmo cálculo arredondando cada parcela primeiro erra por um centavo")
        .isEqualByComparingTo("0.03");
    }
  }

  @Nested
  @DisplayName("iguala valores que só diferem na escala")
  class IgualaValoresQueSoDiferemNaEscala {

    @Test
    @DisplayName("equals ignora a escala de origem")
    void equalsIgnoraAEscalaDeOrigem() {
      assertThat(Money.of(new BigDecimal("342.5")))
        .isEqualTo(Money.of(new BigDecimal("342.50")));
    }

    /**
     * O {@code hashCode} é a metade que costuma ficar para trás: dois objetos iguais que
     * espalham diferente quebram qualquer {@code Set} ou chave de {@code Map} de valor
     * monetário, sem lançar exceção nenhuma.
     */
    @Test
    @DisplayName("hashCode acompanha equals")
    void hashCodeAcompanhaEquals() {
      assertThat(Money.of(new BigDecimal("342.5")).hashCode())
        .isEqualTo(Money.of(new BigDecimal("342.50")).hashCode());
    }

    /**
     * A divergência que a issue apontou vivia aqui: {@code Booking.validateTotalPrice()}
     * decide por {@code isNotEqual}, e se ele discordasse de {@code equals} a reserva seria
     * recusada com {@code BOOKING_TOTAL_PRICE_INVALID} por diferença de escala.
     */
    @Test
    @DisplayName("isNotEqual concorda com equals")
    void isNotEqualConcordaComEquals() {
      final var umaEscala = Money.of(new BigDecimal("342.5"));
      final var outraEscala = Money.of(new BigDecimal("342.50"));

      assertThat(umaEscala.isNotEqual(outraEscala)).isFalse();
      assertThat(umaEscala.isNotEqual(Money.of(new BigDecimal("342.51")))).isTrue();
    }

    @Test
    @DisplayName("independe da fábrica usada")
    void independeDaFabricaUsada() {
      assertThat(Money.of(350))
        .isEqualTo(Money.of(350.0))
        .isEqualTo(Money.of(new BigDecimal("350.0000")));
    }
  }

  @Nested
  @DisplayName("arredonda de forma explícita")
  class ArredondaDeFormaExplicita {

    /**
     * Um valor além da escala de cálculo é arredondado ao virar {@code Money} — no construtor,
     * onde é visível —, e não silenciosamente na gravação, que era o comportamento antigo.
     */
    @Test
    @DisplayName("na construção, quando o valor excede a escala de cálculo")
    void naConstrucaoQuandoOValorExcedeAEscalaDeCalculo() {
      assertThat(Money.of(new BigDecimal("199.99999")).getValue()).isEqualByComparingTo("200.0000");
      assertThat(Money.of(new BigDecimal("199.99994")).getValue()).isEqualByComparingTo("199.9999");
    }

    @Test
    @DisplayName("na apresentação, reduzindo a duas casas")
    void naApresentacaoReduzindoADuasCasas() {
      assertThat(Money.of(new BigDecimal("199.9950")).getPresentationValue()).isEqualByComparingTo("200.00");
      assertThat(Money.of(new BigDecimal("199.9949")).getPresentationValue()).isEqualByComparingTo("199.99");
    }

    /**
     * A escala é o que a API promete, não só o valor: quem serializa a resposta HTTP conta com
     * as duas casas para exibir {@code 342,00} em vez de {@code 342}, e {@code isEqualByComparingTo}
     * não veria a diferença.
     */
    @Test
    @DisplayName("mantendo a escala prometida por cada saída")
    void mantendoAEscalaPrometidaPorCadaSaida() {
      final var money = Money.of(342);

      assertThat(money.getValue().scale()).isEqualTo(4);
      assertThat(money.getPresentationValue().scale()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("ZERO é o elemento neutro da soma")
  void zeroEhOElementoNeutroDaSoma() {
    final var preco = Money.of(new BigDecimal("199.99"));

    assertThat(Money.ZERO.isZero()).isTrue();
    assertThat(Money.ZERO.add(preco)).isEqualTo(preco);
  }
}
