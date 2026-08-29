package com.hotel.booking.system.hotel.service.data.db;

import com.hotel.booking.system.hotel.service.data.db.entity.HotelEntity;
import com.hotel.booking.system.hotel.service.data.db.repository.HotelJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercita a {@code findAllAvailableHotelByParameters}, a única consulta escrita à mão do
 * {@code hotel-service}.
 *
 * <p>A consulta é JPQL puro, então o Hibernate a traduz para o dialeto de cada banco e ela
 * atravessa a migração sem alteração de código. O que <strong>não</strong> atravessa é a
 * semântica de comparação de texto — e é aí que estes testes olham.</p>
 */
@DisplayName("Busca de hotéis por parâmetros")
class HotelJpaRepositoryIT extends AbstractDatabaseIT {

  @Autowired
  private HotelJpaRepository repository;

  @Test
  @DisplayName("sem filtro devolve todos os hotéis do seed")
  void semFiltroDevolveTodos() {
    final var encontrados = this.repository.findAllAvailableHotelByParameters(null, null, null, null);

    assertThat(encontrados).hasSize(5);
  }

  @Test
  @DisplayName("string vazia é tratada como ausência de filtro")
  void stringVaziaNaoFiltra() {
    final var encontrados = this.repository.findAllAvailableHotelByParameters("", "", "", "");

    assertThat(encontrados).hasSize(5);
  }

  @Test
  @DisplayName("filtra por trecho do nome, ignorando a caixa")
  void filtraPorTrechoDoNomeIgnorandoCaixa() {
    final var encontrados = this.repository.findAllAvailableHotelByParameters("AMAZON", null, null, null);

    assertThat(encontrados)
      .extracting(HotelEntity::getName)
      .containsExactly("Amazon Plaza Hotel");
  }

  @Test
  @DisplayName("filtra por cidade e categoria ao mesmo tempo")
  void filtraPorCidadeECategoria() {
    final var encontrados = this.repository.findAllAvailableHotelByParameters(null, "Pousada", "Chapada", null);

    assertThat(encontrados)
      .extracting(HotelEntity::getName)
      .containsExactlyInAnyOrder("Pousada Villa Guiamares", "Pousada Canto dos Pássaros");
  }

  @Test
  @DisplayName("filtra por estado")
  void filtraPorEstado() {
    final var encontrados = this.repository.findAllAvailableHotelByParameters(null, null, null, "Mato Grosso");

    assertThat(encontrados).hasSize(5);
  }

  @Test
  @DisplayName("devolve vazio quando nada casa")
  void devolveVazioQuandoNadaCasa() {
    final var encontrados = this.repository.findAllAvailableHotelByParameters("Ritz", null, null, null);

    assertThat(encontrados).isEmpty();
  }

  /**
   * Este é o teste que a migração precisa manter verde, e ele não é um detalhe de estilo.
   *
   * <p>A consulta protege a caixa com {@code lower()}, mas nada no JPQL trata acento. Quem
   * trata hoje é a colação padrão do MySQL 8, {@code utf8mb4_0900_ai_ci}, cujo {@code ai}
   * significa <em>accent-insensitive</em>: {@code cuiaba} casa com {@code Cuiabá} sem que uma
   * linha de código peça isso. O PostgreSQL compara texto byte a byte e não faz esse favor.</p>
   *
   * <p>Ou seja: buscar hotel por cidade digitada sem acento é uma funcionalidade que existe
   * hoje por acidente de configuração do banco, não por decisão de projeto. Se a Fase 2
   * quebrar este teste, o certo é consertar a Fase 2 — normalizando na consulta ou habilitando
   * a extensão {@code unaccent} —, nunca afrouxar a asserção.</p>
   */
  @Test
  @DisplayName("acha a cidade mesmo quando o termo vem sem acento")
  void achaCidadeSemAcento() {
    final var encontrados = this.repository.findAllAvailableHotelByParameters(null, null, "cuiaba", null);

    assertThat(encontrados)
      .as("comportamento herdado da colação accent-insensitive do MySQL; precisa sobreviver à migração")
      .hasSize(3);
  }

}
