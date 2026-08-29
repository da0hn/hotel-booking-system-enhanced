package com.hotel.booking.system.booking.service.data.db;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trava a forma do schema que o Flyway produz no {@code booking-service}.
 *
 * <p>Como no {@code hotel-service}, a asserção de forma mais forte é o
 * {@code hibernate.ddl-auto: validate} do perfil {@code test}: o contexto só sobe se
 * {@code BookingEntity}, {@code BookingRoomEntity} e {@code RoomEntity} casarem com as
 * colunas migradas. Aqui ficam os comportamentos que o {@code validate} não enxerga.</p>
 */
@DisplayName("Migrations do booking-service")
class FlywayMigrationIT extends AbstractDatabaseIT {

  private static final String QUARTO_TRIPLO_DELUXE = "2223dc04-831a-4bac-aef5-e22195575cc6";

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

    assertThat(aplicadas).containsExactly("001", "002", "003", "004", "005", "006");
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
    "booking; id, customer_id, reservation_order_id, check_in, check_out, status, total_price, created_at, updated_at",
    "room; id, hotel_id, current_price, capacity, quantity",
    "booking_room; id, room_id, booking_id, price, quantity",
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
    "room, 13",
    "booking, 3",
    "booking_room, 4",
  })
  void carregaSeedCompleto(final String tabela, final long esperado) {
    final var total = this.entityManager
      .createNativeQuery("select count(*) from " + tabela)
      .getSingleResult();

    assertThat(((Number) total).longValue()).isEqualTo(esperado);
  }

  /**
   * A V006 acrescenta {@code created_at} e {@code updated_at} como
   * {@code timestamp with time zone default now()}. O tipo era {@code datetime} enquanto o
   * banco era MySQL, e o que se perde numa tradução desatenta é o default — que é o que este
   * teste cobra: as colunas são preenchidas mesmo quando o insert não as menciona.
   */
  @Test
  @DisplayName("preenche created_at por default quando o insert não informa")
  void preencheCreatedAtPorDefault() {
    final var id = UUID.randomUUID();

    this.entityManager
      .createNativeQuery("""
        insert into booking (id, customer_id, reservation_order_id, check_in, check_out, status, total_price)
        values (:id, :cliente, :pedido, '2026-01-10', '2026-01-15', 'PENDING', 500)
        """)
      .setParameter("id", id.toString())
      .setParameter("cliente", UUID.randomUUID().toString())
      .setParameter("pedido", UUID.randomUUID().toString())
      .executeUpdate();
    this.entityManager.flush();
    this.entityManager.clear();

    final var criadoEm = this.entityManager
      .createNativeQuery("select created_at from booking where id = :id")
      .setParameter("id", id.toString())
      .getSingleResult();

    assertThat(criadoEm)
      .as("o default `now()` da V006 sobreviveu à tradução do tipo")
      .isNotNull();
  }

  /**
   * O {@code BookingEntity} mapeia {@code created_at} como {@link Instant}, e o
   * {@code @PrePersist} grava {@code Instant.now()}. O tipo do banco não guarda fuso: a hora
   * gravada é a que a JVM enviou, e a JVM roda em {@code America/Cuiaba} nos containers de
   * serviço. Este teste prova que o valor volta igual ao que saiu, o que é o único contrato
   * que o código depende.
   */
  @Test
  @DisplayName("devolve o instante gravado sem deslocar o fuso")
  void devolveInstanteGravadoSemDeslocarFuso() {
    final var id = UUID.randomUUID();
    final var momento = Instant.parse("2026-03-15T18:30:45Z");

    this.entityManager
      .createNativeQuery("""
        insert into booking (id, customer_id, reservation_order_id, check_in, check_out, status, total_price, created_at)
        values (:id, :cliente, :pedido, '2026-03-20', '2026-03-25', 'PENDING', 500, :criadoEm)
        """)
      .setParameter("id", id.toString())
      .setParameter("cliente", UUID.randomUUID().toString())
      .setParameter("pedido", UUID.randomUUID().toString())
      .setParameter("criadoEm", momento)
      .executeUpdate();
    this.entityManager.flush();
    this.entityManager.clear();

    final var lido = this.entityManager
      .createQuery("select b.createdAt from BookingEntity b where b.id = :id", Instant.class)
      .setParameter("id", id)
      .getSingleResult();

    assertThat(lido).isEqualTo(momento);
  }

  @Test
  @DisplayName("mantém a chave estrangeira de booking_room para booking")
  void mantemChaveEstrangeiraDeBookingRoomParaBooking() {
    assertThatThrownBy(() -> {
      this.entityManager
        .createNativeQuery("""
          insert into booking_room (id, room_id, booking_id, price, quantity)
          values (:id, :quarto, :reservaInexistente, 100, 1)
          """)
        .setParameter("id", UUID.randomUUID().toString())
        .setParameter("quarto", QUARTO_TRIPLO_DELUXE)
        .setParameter("reservaInexistente", UUID.randomUUID().toString())
        .executeUpdate();
      this.entityManager.flush();
    }).isInstanceOf(Exception.class);
  }

  /**
   * O mesmo arredondamento que o {@code hotel-service} sofria em {@code room.current_price}
   * atingia aqui três colunas: {@code booking.total_price}, {@code room.current_price} e
   * {@code booking_room.price}. Era o preço da reserva inteira que perdia os centavos.
   *
   * <p>Como no {@code hotel-service}, o teste nasceu cobrando o valor arredondado e quebrou
   * no commit da migração. A asserção de hoje é o registro da correção.</p>
   */
  @Test
  @DisplayName("preserva os centavos no total da reserva")
  void preservaCentavosNoTotalDaReserva() {
    final var id = UUID.randomUUID();

    this.entityManager
      .createNativeQuery("""
        insert into booking (id, customer_id, reservation_order_id, check_in, check_out, status, total_price)
        values (:id, :cliente, :pedido, '2026-02-10', '2026-02-15', 'PENDING', :total)
        """)
      .setParameter("id", id.toString())
      .setParameter("cliente", UUID.randomUUID().toString())
      .setParameter("pedido", UUID.randomUUID().toString())
      .setParameter("total", new BigDecimal("1234.56"))
      .executeUpdate();
    this.entityManager.flush();
    this.entityManager.clear();

    final var gravado = (BigDecimal) this.entityManager
      .createNativeQuery("select total_price from booking where id = :id")
      .setParameter("id", id.toString())
      .getSingleResult();

    assertThat(gravado)
      .as("`numeric(10, 2)`; o `DECIMAL(10,0)` herdado do MySQL devolvia 1235")
      .isEqualByComparingTo("1234.56");
  }

}
