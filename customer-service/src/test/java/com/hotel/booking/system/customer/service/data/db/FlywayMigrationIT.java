package com.hotel.booking.system.customer.service.data.db;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trava a forma do schema que o Flyway produz no {@code customer-service}.
 *
 * <p>Este é o módulo com a migração mais frágil dos três. A V003 declarava a chave primária
 * como {@code constraint primary key (id)} — um {@code CONSTRAINT} sem nome, que o MySQL
 * aceita e o PostgreSQL recusa —, e a V005 alargava uma coluna com {@code alter table ...
 * modify}. Tudo o que este teste afirma passa por essas duas migrations, e foram elas as
 * duas linhas de SQL do projeto inteiro que o PostgreSQL simplesmente não compila.</p>
 */
@DisplayName("Migrations do customer-service")
class FlywayMigrationIT extends AbstractDatabaseIT {

  private static final String CLIENTE_GABRIEL = "f1e28a47-8852-45e8-b8e1-e6701633dd56";

  @Autowired
  private EntityManager entityManager;

  /**
   * O {@code version is not null} descarta uma linha só, e ela não é ruído: com
   * {@code spring.flyway.create-schemas: true}, o Flyway registra no histórico um marcador sem
   * versão dizendo quais schemas ele próprio criou — é por ele que um {@code clean} sabe o que
   * pode derrubar. A linha nasceu com o layout de um schema por serviço.
   */
  @Test
  @DisplayName("aplica todas as versões sem falha")
  void aplicaTodasAsVersoes() {
    final var aplicadas = this.entityManager
      .createNativeQuery("select version from flyway_schema_history where success = true and version is not null order by installed_rank")
      .getResultList();

    assertThat(aplicadas).containsExactly("001", "002", "003", "004", "005", "006", "007", "008");
  }

  @Test
  @DisplayName("não deixa nenhuma versão com falha registrada")
  void naoDeixaVersaoComFalha() {
    final var falhas = this.entityManager
      .createNativeQuery("select count(*) from flyway_schema_history where success = false")
      .getSingleResult();

    assertThat(((Number) falhas).longValue()).isZero();
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("cria as tabelas com as colunas esperadas")
  @CsvSource(delimiter = ';', value = {
    "customer; id, name, cpf",
    "reservation_order; id, customer_id, hotel_id, guests, check_in, check_out, total_price, current_status",
    "reservation_order_history; id, reservation_order_id, status, occurred_at, failure_reason",
  })
  void criaTabelasComColunasEsperadas(final String tabela, final String colunas) {
    final var resultado = this.entityManager
      .createNativeQuery("select %s from %s where 1 = 0".formatted(colunas, tabela))
      .getResultList();

    assertThat(resultado).isEmpty();
  }

  @Test
  @DisplayName("carrega os quatro clientes do seed")
  void carregaOsQuatroClientesDoSeed() {
    final var total = this.entityManager
      .createNativeQuery("select count(*) from customer")
      .getSingleResult();

    assertThat(((Number) total).longValue()).isEqualTo(4L);
  }

  /**
   * A V003 escrevia a chave primária como {@code constraint primary key (id)}, sem nome — o
   * MySQL tolera, o PostgreSQL recusa a sintaxe. A tradução tinha que dar um nome à constraint,
   * e é isso que este teste guarda: uma tradução que apagasse a chave deixaria a tabela
   * existindo e as consultas funcionando, com a timeline aceitando duplicatas em silêncio.
   */
  @Test
  @DisplayName("mantém a chave primária da timeline")
  void mantemChavePrimariaDaTimeline() {
    final var id = UUID.randomUUID();
    final var pedido = this.inserirPedido();
    this.inserirHistorico(id, pedido);

    assertThatThrownBy(() -> this.inserirHistorico(id, pedido))
      .as("a PK da V003 nasceu sem nome, sintaxe que só o MySQL aceita; a tradução precisou nomeá-la")
      .isInstanceOf(Exception.class);
  }

  @Test
  @DisplayName("mantém a chave estrangeira da timeline para o pedido")
  void mantemChaveEstrangeiraDaTimelineParaPedido() {
    assertThatThrownBy(() -> this.inserirHistorico(UUID.randomUUID(), UUID.randomUUID()))
      .isInstanceOf(Exception.class);
  }

  /**
   * A V006 acrescenta a chave estrangeira de {@code reservation_order} para {@code customer}
   * num {@code alter table} separado, três migrations depois de a tabela nascer.
   */
  @Test
  @DisplayName("mantém a chave estrangeira do pedido para o cliente")
  void mantemChaveEstrangeiraDoPedidoParaCliente() {
    assertThatThrownBy(() -> {
      this.entityManager
        .createNativeQuery("""
          insert into reservation_order (id, customer_id, hotel_id, guests, check_in, check_out, total_price, current_status)
          values (:id, :clienteInexistente, :hotel, 2, '2026-04-10', '2026-04-15', 800, 'AWAITING_RESERVATION')
          """)
        .setParameter("id", UUID.randomUUID().toString())
        .setParameter("clienteInexistente", UUID.randomUUID().toString())
        .setParameter("hotel", UUID.randomUUID().toString())
        .executeUpdate();
      this.entityManager.flush();
    }).isInstanceOf(Exception.class);
  }

  /**
   * A V005 alarga {@code customer.name} de 36 para 50. A sintaxe era {@code alter table ...
   * modify}, que o PostgreSQL não aceita; hoje é {@code alter column ... type}.
   */
  @Test
  @DisplayName("alarga o nome do cliente para 50 caracteres")
  void alargaNomeDoClientePara50() {
    final var nomeLongo = "n".repeat(50);
    final var id = UUID.randomUUID();

    this.entityManager
      .createNativeQuery("insert into customer (id, name, cpf) values (:id, :nome, '55896120044')")
      .setParameter("id", id.toString())
      .setParameter("nome", nomeLongo)
      .executeUpdate();
    this.entityManager.flush();
    this.entityManager.clear();

    final var gravado = this.entityManager
      .createNativeQuery("select name from customer where id = :id")
      .setParameter("id", id.toString())
      .getSingleResult();

    assertThat(gravado).isEqualTo(nomeLongo);
  }

  /**
   * A V007 acrescenta {@code failure_reason varchar(8000)}. O MySQL comportava esse tamanho num
   * {@code VARCHAR} só porque a linha inteira cabia no limite de 65.535 bytes — era um limite
   * de linha, não de coluna. O PostgreSQL não tem esse teto. O teste garante que a tradução não
   * encolheu a coluna e não passou a truncar a mensagem de erro que o cliente lê na timeline.
   */
  @Test
  @DisplayName("comporta uma razão de falha de 8000 caracteres")
  void comportaRazaoDeFalhaDe8000Caracteres() {
    final var razaoLonga = "e".repeat(8000);
    final var id = UUID.randomUUID();
    final var pedido = this.inserirPedido();

    this.entityManager
      .createNativeQuery("""
        insert into reservation_order_history (id, reservation_order_id, status, occurred_at, failure_reason)
        values (:id, :pedido, 'PAYMENT_FAILED', '2026-05-01 10:00:00', :razao)
        """)
      .setParameter("id", id.toString())
      .setParameter("pedido", pedido.toString())
      .setParameter("razao", razaoLonga)
      .executeUpdate();
    this.entityManager.flush();
    this.entityManager.clear();

    final var gravada = this.entityManager
      .createNativeQuery("select failure_reason from reservation_order_history where id = :id")
      .setParameter("id", id.toString())
      .getSingleResult();

    assertThat(gravada).isEqualTo(razaoLonga);
  }

  /**
   * O {@code customer-service} é read-model: ele não soma nem multiplica nada, só guarda o
   * total que o {@code booking-service} calculou e mandou no evento. Por isso a escala aqui não
   * protege um cálculo — protege a fidelidade da projeção. Uma coluna mais curta que a de
   * origem faria a timeline mostrar ao cliente um total que difere do que foi cobrado, e nada
   * acusaria: não há exceção, só um arredondamento na gravação.
   */
  @Test
  @DisplayName("preserva a escala de cálculo do total recebido do booking")
  void preservaAEscalaDeCalculoDoTotalRecebidoDoBooking() {
    final var id = this.inserirPedido(new BigDecimal("1234.5678"));
    this.entityManager.clear();

    final var gravado = (BigDecimal) this.entityManager
      .createNativeQuery("select total_price from reservation_order where id = :id")
      .setParameter("id", id.toString())
      .getSingleResult();

    assertThat(gravado)
      .as("`numeric(19, 4)`; com a escala 2 anterior o total voltaria 1234.57")
      .isEqualByComparingTo("1234.5678");
  }

  private UUID inserirPedido() {
    return this.inserirPedido(new BigDecimal("800"));
  }

  private UUID inserirPedido(final BigDecimal total) {
    final var id = UUID.randomUUID();
    this.entityManager
      .createNativeQuery("""
        insert into reservation_order (id, customer_id, hotel_id, guests, check_in, check_out, total_price, current_status)
        values (:id, :cliente, :hotel, 2, '2026-04-10', '2026-04-15', :total, 'AWAITING_RESERVATION')
        """)
      .setParameter("id", id.toString())
      .setParameter("cliente", CLIENTE_GABRIEL)
      .setParameter("hotel", UUID.randomUUID().toString())
      .setParameter("total", total)
      .executeUpdate();
    this.entityManager.flush();
    return id;
  }

  private void inserirHistorico(final UUID id, final UUID pedido) {
    this.entityManager
      .createNativeQuery("""
        insert into reservation_order_history (id, reservation_order_id, status, occurred_at)
        values (:id, :pedido, 'AWAITING_RESERVATION', '2026-05-01 10:00:00')
        """)
      .setParameter("id", id.toString())
      .setParameter("pedido", pedido.toString())
      .executeUpdate();
    this.entityManager.flush();
  }

}
