package com.hotel.booking.system.hotel.service.data.db;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prova que um serviço não alcança o dado de outro.
 *
 * <p>Enquanto eram três instâncias de MySQL, esta garantia não precisava de teste: não havia
 * rota entre elas. Com um schema por serviço na mesma instância a rota existe, e o que a
 * fecha é o usuário — cada serviço conecta com o seu, e um schema pertence a quem o criou.
 * O que era topologia virou privilégio, e privilégio se configura errado em silêncio.</p>
 *
 * <p>O teste é assimétrico de propósito: o container deste módulo só tem o schema
 * {@code hotel}, então o que dá para provar aqui é que o papel de <em>outro</em> serviço
 * bate na porta e não entra. É a direção que importa — a que falharia se o
 * {@code 01-create-service-roles.sql} concedesse privilégio demais.</p>
 */
@DisplayName("Isolamento entre os schemas dos serviços")
class IsolacaoEntreSchemasIT extends AbstractDatabaseIT {

  private static final String USUARIO_DE_OUTRO_SERVICO = "user_booking_service";

  /** {@code insufficient_privilege}. A asserção é pelo SQLState porque a mensagem do PostgreSQL muda com a locale. */
  private static final String PRIVILEGIO_INSUFICIENTE = "42501";

  @Autowired
  private EntityManager entityManager;

  /**
   * A premissa de todos os outros testes deste módulo, e a que falharia calada.
   *
   * <p>Se o contexto do Spring conectasse com o superusuário do container — porque o
   * {@code @DynamicPropertySource} não pegou, ou porque alguém devolveu o
   * {@code @ServiceConnection} —, as migrations rodariam com privilégio que a aplicação não
   * tem, e nada acusaria. O teste de privilégio insuficiente aqui embaixo continuaria verde,
   * porque abre a própria conexão.</p>
   */
  @Test
  @DisplayName("o contexto conecta com o usuário do serviço, e não com o dono do container")
  void contextoConectaComOUsuarioDoServico() {
    final var usuarioDaConexao = this.entityManager
      .createNativeQuery("select current_user")
      .getSingleResult();

    assertThat(usuarioDaConexao).isEqualTo(USUARIO);
  }

  /**
   * O que fecha a rota entre os schemas não é um {@code REVOKE}, é a posse: quem cria um
   * schema é dono dele, e ninguém mais recebe {@code USAGE}. Se o schema passasse a nascer
   * de outro papel — de um bootstrap que o pré-criasse, por exemplo —, o isolamento sumiria
   * sem que nenhuma linha de configuração mudasse.
   */
  @Test
  @DisplayName("o schema pertence ao usuário do próprio serviço")
  void schemaPertenceAoUsuarioDoServico() {
    final var dono = this.entityManager
      .createNativeQuery("select schema_owner from information_schema.schemata where schema_name = 'hotel'")
      .getSingleResult();

    assertThat(dono).isEqualTo(USUARIO);
  }

  /**
   * Controle positivo, e não redundância: sem ele, uma senha errada no script faria o teste
   * seguinte passar pelo motivo errado — recusa de login em vez de recusa de leitura.
   */
  @Test
  @DisplayName("o usuário de outro serviço conecta no banco normalmente")
  void outroServicoConectaNoBanco() {
    assertThatCode(() -> this.conectarComo(USUARIO_DE_OUTRO_SERVICO).close())
      .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("o usuário de outro serviço não lê o schema do hotel")
  void outroServicoNaoLeOSchemaDoHotel() {
    assertThatThrownBy(() -> {
      try (final var conexao = this.conectarComo(USUARIO_DE_OUTRO_SERVICO);
           final var comando = conexao.createStatement()) {
        comando.executeQuery("select id from hotel.hotel");
      }
    })
      .as("o schema pertence ao user_hotel_service e ninguém mais recebe USAGE nele")
      .isInstanceOfSatisfying(SQLException.class,
        erro -> assertThat(erro.getSQLState()).isEqualTo(PRIVILEGIO_INSUFICIENTE));
  }

  /**
   * O superusuário atravessa qualquer permissão de schema. Se o papel do serviço virasse um
   * por descuido no script de criação, os dois testes acima passariam a não provar nada.
   */
  @Test
  @DisplayName("o usuário do próprio serviço não é superusuário")
  void usuarioDoServicoNaoESuperusuario() throws SQLException {
    try (final var conexao = this.conectarComo(USUARIO);
         final var comando = conexao.createStatement();
         final var resultado = comando.executeQuery("select usesuper from pg_user where usename = current_user")) {

      assertThat(resultado.next()).isTrue();
      assertThat(resultado.getBoolean(1)).isFalse();
    }
  }

  private Connection conectarComo(final String usuario) throws SQLException {
    return DriverManager.getConnection(DATABASE.getJdbcUrl(), usuario, SENHA);
  }

}
