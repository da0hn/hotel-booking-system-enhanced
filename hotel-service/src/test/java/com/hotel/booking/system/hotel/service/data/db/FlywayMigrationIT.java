package com.hotel.booking.system.hotel.service.data.db;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trava a forma do schema que o Flyway produz no {@code hotel-service}.
 *
 * <p>A asserção mais forte desta classe não está em nenhum método: é o
 * {@code hibernate.ddl-auto: validate} do perfil {@code test}. Se o contexto sobe, o
 * Hibernate já confrontou {@code HotelEntity}, {@code RoomEntity}, {@code LocalityEntity} e
 * {@code HotelCategoryEntity} contra as colunas reais e não achou divergência. Os testes
 * abaixo cobrem o que o {@code validate} não olha: dados de seed, acentuação e precisão.</p>
 */
@DisplayName("Migrations do hotel-service")
class FlywayMigrationIT extends AbstractDatabaseIT {

  private static final String HOTEL_AMAZON_PLAZA = "b69b768f-32d8-4c70-a3ef-b5ace438c5e7";
  private static final String CATEGORIA_HOTEL = "bcbc43a4-5a77-44e8-9cd4-7da67b66a390";
  private static final String LOCALIDADE_CUIABA = "2e02993c-2b70-478e-82b2-63ff7a4991c1";

  @Autowired
  private EntityManager entityManager;

  @Test
  @DisplayName("aplica todas as versões sem falha")
  void aplicaTodasAsVersoes() {
    final var aplicadas = this.entityManager
      .createNativeQuery("select version from flyway_schema_history where success = true order by installed_rank")
      .getResultList();

    assertThat(aplicadas)
      .as("uma migration nova precisa ser somada aqui de propósito, para que a lista continue sendo uma trava")
      .containsExactly("001", "002", "003", "004", "005", "006", "007", "008", "009", "010");
  }

  @Test
  @DisplayName("não deixa nenhuma versão com falha registrada")
  void naoDeixaVersaoComFalha() {
    final var falhas = this.entityManager
      .createNativeQuery("select count(*) from flyway_schema_history where success = false")
      .getSingleResult();

    assertThat(((Number) falhas).longValue()).isZero();
  }

  /**
   * Prova que a tabela existe e que as colunas se chamam o que o código espera, sem depender
   * de {@code DatabaseMetaData}: o {@code where 1 = 0} faz o banco resolver os nomes e não
   * devolver linha nenhuma. É portável entre MySQL e PostgreSQL, ao contrário dos nomes de
   * tipo, que divergem ({@code DECIMAL} contra {@code numeric}, {@code datetime} contra
   * {@code timestamp}).
   */
  @ParameterizedTest(name = "{0}")
  @DisplayName("cria as tabelas com as colunas esperadas")
  @CsvSource(delimiter = ';', value = {
    "hotel_category; id, name",
    "locality; id, city, state, country",
    "hotel; id, name, description, hotel_cep, hotel_street, locality_id, category_id",
    "room; id, name, description, capacity, current_price, hotel_id, quantity",
  })
  void criaTabelasComColunasEsperadas(final String tabela, final String colunas) {
    final var resultado = this.entityManager
      .createNativeQuery("select %s from %s where 1 = 0".formatted(colunas, tabela))
      .getResultList();

    assertThat(resultado).isEmpty();
  }

  @ParameterizedTest(name = "{0} = {1}")
  @DisplayName("carrega o seed completo")
  @CsvSource({
    "hotel_category, 7",
    "locality, 6",
    "hotel, 5",
    "room, 13",
  })
  void carregaSeedCompleto(final String tabela, final long esperado) {
    final var total = this.entityManager
      .createNativeQuery("select count(*) from " + tabela)
      .getSingleResult();

    assertThat(((Number) total).longValue()).isEqualTo(esperado);
  }

  @ParameterizedTest
  @DisplayName("preserva os acentos do seed")
  @ValueSource(strings = {"Cuiabá", "Várzea Grande", "Chapada dos Guimarães", "Rondonópolis", "São Paulo"})
  void preservaAcentosDoSeed(final String cidade) {
    final var encontradas = this.entityManager
      .createNativeQuery("select city from locality where city = :cidade")
      .setParameter("cidade", cidade)
      .getResultList();

    assertThat(encontradas)
      .as("um banco criado com charset errado devolveria a cidade corrompida, ou não a acharia")
      .containsExactly(cidade);
  }

  @Test
  @DisplayName("mantém a chave estrangeira de room para hotel")
  void mantemChaveEstrangeiraDeRoomParaHotel() {
    assertThatThrownBy(() -> this.inserirQuarto(UUID.randomUUID(), UUID.randomUUID().toString(), "100"))
      .as("a V006 declara a FK; se ela sumir o insert passa e o banco aceita quarto sem hotel")
      .isInstanceOf(Exception.class);
  }

  @Test
  @DisplayName("recusa quarto sem preço")
  void recusaQuartoSemPreco() {
    assertThatThrownBy(() -> this.inserirQuarto(UUID.randomUUID(), HOTEL_AMAZON_PLAZA, null))
      .isInstanceOf(Exception.class);
  }

  /**
   * Este teste afirma o comportamento <strong>errado</strong> de hoje, de propósito.
   *
   * <p>As migrations declaram {@code current_price decimal} sem precisão. O MySQL lê isso como
   * {@code DECIMAL(10,0)} — zero casas decimais — e arredonda todo centavo que entra. Nenhum
   * teste pegou isso em três anos porque todos os preços do seed são inteiros.</p>
   *
   * <p>Na Fase 2 este teste <strong>tem</strong> que quebrar: o PostgreSQL trata
   * {@code numeric} sem precisão como precisão arbitrária e devolve {@code 199.99}. A quebra é
   * a prova de que a issue #1 foi corrigida, e a asserção é atualizada no mesmo commit da
   * migração — o diff passa a ser o registro da mudança intencional.</p>
   */
  @Test
  @DisplayName("arredonda centavos no preço do quarto (comportamento a corrigir na migração)")
  void arredondaCentavosNoPrecoDoQuarto() {
    final var id = UUID.randomUUID();
    this.inserirQuarto(id, HOTEL_AMAZON_PLAZA, "199.99");
    this.entityManager.clear();

    final var gravado = (BigDecimal) this.entityManager
      .createNativeQuery("select current_price from room where id = :id")
      .setParameter("id", id.toString())
      .getSingleResult();

    assertThat(gravado)
      .as("DECIMAL(10,0) no MySQL; deve virar 199.99 quando a coluna for numeric no PostgreSQL")
      .isEqualByComparingTo("200");
  }

  /**
   * A V007 e a V009 alargam colunas com {@code alter table ... modify}, sintaxe que o
   * PostgreSQL não aceita. Um {@code alter} traduzido errado na Fase 2 deixaria a coluna no
   * tamanho original, e este teste é o primeiro a notar.
   */
  @Test
  @DisplayName("alarga a descrição do hotel para 500 caracteres")
  void alargaDescricaoDoHotelPara500() {
    final var descricaoLonga = "x".repeat(500);
    final var id = UUID.randomUUID();

    this.entityManager
      .createNativeQuery("""
        insert into hotel (id, name, description, hotel_cep, hotel_street, category_id, locality_id)
        values (:id, 'Hotel de teste', :descricao, '78005-370', 'Rua de teste, 1', :categoria, :localidade)
        """)
      .setParameter("id", id.toString())
      .setParameter("descricao", descricaoLonga)
      .setParameter("categoria", CATEGORIA_HOTEL)
      .setParameter("localidade", LOCALIDADE_CUIABA)
      .executeUpdate();
    this.entityManager.flush();
    this.entityManager.clear();

    final var gravada = this.entityManager
      .createNativeQuery("select description from hotel where id = :id")
      .setParameter("id", id.toString())
      .getSingleResult();

    assertThat(gravada).isEqualTo(descricaoLonga);
  }

  private void inserirQuarto(final UUID id, final String hotelId, final String preco) {
    this.entityManager
      .createNativeQuery("""
        insert into room (id, name, description, capacity, current_price, hotel_id, quantity)
        values (:id, 'Quarto de teste', 'Existe só para medir a coluna', 2, :preco, :hotel, 1)
        """)
      .setParameter("id", id.toString())
      .setParameter("preco", preco == null ? null : new BigDecimal(preco))
      .setParameter("hotel", hotelId)
      .executeUpdate();
    this.entityManager.flush();
  }

}
