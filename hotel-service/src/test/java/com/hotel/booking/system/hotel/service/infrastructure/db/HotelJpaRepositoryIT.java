package com.hotel.booking.system.hotel.service.infrastructure.db;

import com.hotel.booking.system.hotel.service.infrastructure.db.entity.HotelCategoryEntity;
import com.hotel.booking.system.hotel.service.infrastructure.db.entity.HotelEntity;
import com.hotel.booking.system.hotel.service.infrastructure.db.entity.LocalityEntity;
import com.hotel.booking.system.hotel.service.infrastructure.db.repository.HotelJpaRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

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

  private static final UUID CATEGORIA_HOTEL = UUID.fromString("bcbc43a4-5a77-44e8-9cd4-7da67b66a390");

  private static final UUID LOCALIDADE_CUIABA = UUID.fromString("2e02993c-2b70-478e-82b2-63ff7a4991c1");

  @Autowired
  private HotelJpaRepository repository;

  @Autowired
  private EntityManager entityManager;

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
   * A busca paginada é a mesma consulta com {@code ORDER BY name, id} e um {@code Slice}: o Spring
   * Data pede {@code size + 1} linhas e decide o {@code hasNext} pela sobra, sem {@code COUNT}.
   * Estes testes olham o que um erro aí quebraria sem barulho: hotel repetido ou perdido entre
   * páginas, ordem instável no empate e filtro que deixa de valer na página.
   */
  @Test
  @DisplayName("a página percorre todos os hotéis sem repetir nem omitir, e hasNext só é falso na última")
  void paginaPercorreTodosOsHoteisSemRepetirNemOmitir() {
    final var primeira = this.repository.findAvailableHotelPageByParameters(null, null, null, null, pagina(0, 2));
    final var segunda = this.repository.findAvailableHotelPageByParameters(null, null, null, null, pagina(1, 2));
    final var ultima = this.repository.findAvailableHotelPageByParameters(null, null, null, null, pagina(2, 2));

    assertThat(primeira.getContent()).hasSize(2);
    assertThat(primeira.hasNext()).isTrue();
    assertThat(segunda.getContent()).hasSize(2);
    assertThat(segunda.hasNext()).isTrue();
    assertThat(ultima.getContent()).hasSize(1);
    assertThat(ultima.hasNext()).isFalse();
    assertThat(Stream.of(primeira, segunda, ultima).flatMap(slice -> slice.getContent().stream()))
      .extracting(HotelEntity::getId)
      .doesNotHaveDuplicates()
      .containsExactlyInAnyOrderElementsOf(
        this.repository.findAllAvailableHotelByParameters(null, null, null, null).stream().map(HotelEntity::getId).toList()
      );
  }

  @Test
  @DisplayName("a última página cheia não tem próxima: o elemento extra é que acusa a seguinte")
  void ultimaPaginaCheiaNaoTemProxima() {
    final var pagina = this.repository.findAvailableHotelPageByParameters(null, null, null, null, pagina(0, 5));

    assertThat(pagina.getContent()).hasSize(5);
    assertThat(pagina.hasNext()).isFalse();
  }

  /**
   * Trava a decisão central da paginação: {@code hasNext} sai do elemento extra de um
   * {@code Slice}, sem um {@code COUNT} por requisição. Se o retorno virasse {@code Page}, os
   * outros testes continuariam verdes (só leem {@code getContent()} e {@code hasNext()}) e cada
   * busca passaria a emitir duas consultas; só a contagem de execuções acusa isso.
   *
   * <p>As estatísticas do Hibernate são ligadas só durante o teste, para não pagar um contexto do
   * Spring à parte por uma propriedade.</p>
   */
  @Test
  @DisplayName("a página sai de uma única consulta, sem COUNT")
  void paginaSaiDeUmaUnicaConsultaSemCount() {
    final var statistics = this.entityManager.getEntityManagerFactory()
      .unwrap(SessionFactory.class)
      .getStatistics();
    statistics.setStatisticsEnabled(true);
    try {
      statistics.clear();

      final var pagina = this.repository.findAvailableHotelPageByParameters(null, null, null, null, pagina(0, 2));

      assertThat(pagina.getContent()).hasSize(2);
      assertThat(pagina.hasNext()).isTrue();
      assertThat(statistics.getQueryExecutionCount()).isEqualTo(1L);
    } finally {
      statistics.setStatisticsEnabled(false);
    }
  }

  @Test
  @DisplayName("lista por nome e, no empate, por id")
  void listaPorNomeEDesempataPorId() {
    final var ids = List.of(
      "f0000000-0000-4000-8000-000000000001",
      "a0000000-0000-4000-8000-000000000002",
      "c0000000-0000-4000-8000-000000000003"
    );
    ids.forEach(id -> this.persistirHotel(id, "Zeta Empate"));
    this.entityManager.flush();
    this.entityManager.clear();

    final var primeira = this.repository.findAvailableHotelPageByParameters("Zeta Empate", null, null, null, pagina(0, 2));
    final var segunda = this.repository.findAvailableHotelPageByParameters("Zeta Empate", null, null, null, pagina(1, 2));

    assertThat(primeira.getContent()).extracting(hotel -> hotel.getId().toString())
      .containsExactly("a0000000-0000-4000-8000-000000000002", "c0000000-0000-4000-8000-000000000003");
    assertThat(primeira.hasNext()).isTrue();
    assertThat(segunda.getContent()).extracting(hotel -> hotel.getId().toString())
      .containsExactly("f0000000-0000-4000-8000-000000000001");
    assertThat(segunda.hasNext()).isFalse();
  }

  @Test
  @DisplayName("ordena pelo nome antes do id")
  void ordenaPeloNomeAntesDoId() {
    this.persistirHotel("f0000000-0000-4000-8000-000000000001", "Alfa Ordem");
    this.persistirHotel("a0000000-0000-4000-8000-000000000002", "Beta Ordem");
    this.entityManager.flush();
    this.entityManager.clear();

    final var pagina = this.repository.findAvailableHotelPageByParameters("Ordem", null, null, null, pagina(0, 10));

    assertThat(pagina.getContent()).extracting(HotelEntity::getName).containsExactly("Alfa Ordem", "Beta Ordem");
  }

  @Test
  @DisplayName("a página só contém hotéis que atendem aos filtros, inclusive sem acento")
  void paginaRespeitaOsFiltros() {
    final var primeira = this.repository.findAvailableHotelPageByParameters(null, null, "cuiaba", null, pagina(0, 2));
    final var segunda = this.repository.findAvailableHotelPageByParameters(null, null, "cuiaba", null, pagina(1, 2));

    assertThat(primeira.getContent()).hasSize(2);
    assertThat(primeira.hasNext()).isTrue();
    assertThat(segunda.getContent()).hasSize(1);
    assertThat(segunda.hasNext()).isFalse();
    assertThat(Stream.concat(primeira.getContent().stream(), segunda.getContent().stream()))
      .extracting(hotel -> hotel.getLocality().getCity())
      .containsOnly("Cuiabá");
  }

  @Test
  @DisplayName("a página além do fim é vazia e não tem próxima")
  void paginaAlemDoFimEVazia() {
    final var pagina = this.repository.findAvailableHotelPageByParameters(null, null, null, null, pagina(9, 20));

    assertThat(pagina.getContent()).isEmpty();
    assertThat(pagina.hasNext()).isFalse();
  }

  @Test
  @DisplayName("a página de uma busca sem resultado é vazia")
  void paginaSemResultadoEVazia() {
    final var pagina = this.repository.findAvailableHotelPageByParameters("Ritz", null, null, null, pagina(0, 20));

    assertThat(pagina.getContent()).isEmpty();
    assertThat(pagina.hasNext()).isFalse();
  }

  private static Pageable pagina(final int numero, final int tamanho) {
    return Pageable.ofSize(tamanho).withPage(numero);
  }

  private void persistirHotel(final String id, final String nome) {
    this.entityManager.persist(
      HotelEntity.builder()
        .id(UUID.fromString(id))
        .name(nome)
        .description("Hotel criado pelo teste")
        .hotelCep("78000-000")
        .hotelStreet("Rua do Teste")
        .category(this.entityManager.getReference(HotelCategoryEntity.class, CATEGORIA_HOTEL))
        .locality(this.entityManager.getReference(LocalityEntity.class, LOCALIDADE_CUIABA))
        .rooms(new LinkedHashSet<>())
        .build()
    );
  }

  /**
   * Este é o teste que a migração precisava manter verde, e ele não é um detalhe de estilo.
   *
   * <p>A consulta sempre protegeu a caixa com {@code lower()}, mas nada no JPQL tratava acento.
   * Quem tratava era a colação padrão do MySQL 8, {@code utf8mb4_0900_ai_ci}, cujo {@code ai}
   * significa <em>accent-insensitive</em>: {@code cuiaba} casava com {@code Cuiabá} sem que uma
   * linha de código pedisse isso. O PostgreSQL compara texto byte a byte e não faz esse
   * favor.</p>
   *
   * <p>Ou seja: buscar hotel por cidade digitada sem acento era uma funcionalidade que existia
   * por acidente de configuração do banco, e não por decisão de projeto. Sem este teste, a
   * migração a teria removido sem erro nenhum — nem de compilação, nem de arranque, nem de
   * execução: a busca simplesmente passaria a devolver menos hotéis.</p>
   *
   * <p>Hoje ela é decisão: a {@code @Query} chama {@code unaccent} dos dois lados da
   * comparação. Se este teste quebrar, o certo é consertar a busca, nunca afrouxar a
   * asserção.</p>
   */
  @Test
  @DisplayName("acha a cidade mesmo quando o termo vem sem acento")
  void achaCidadeSemAcento() {
    final var encontrados = this.repository.findAllAvailableHotelByParameters(null, null, "cuiaba", null);

    assertThat(encontrados)
      .as("herdado da colação accent-insensitive do MySQL, hoje pedido explicitamente com `unaccent`")
      .hasSize(3);
  }

}
