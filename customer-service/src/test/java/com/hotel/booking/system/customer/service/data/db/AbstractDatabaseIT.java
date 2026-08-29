package com.hotel.booking.system.customer.service.data.db;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

/**
 * Base dos testes de integração de persistência deste serviço.
 *
 * <p>O container é o único ponto do módulo que sabe qual banco está por baixo. Trocar de
 * engine é trocar esta declaração — as subclasses não mencionam MySQL nem PostgreSQL, e é
 * por isso que elas provam <em>equivalência</em>: o mesmo teste rodou contra os dois.</p>
 *
 * <p>A imagem é a mesma do {@code docker/common.yml}. Um teste contra uma versão diferente
 * da que roda em produção mede o banco errado.</p>
 *
 * <p><strong>A conexão usa {@code __USUARIO__}, e não o superusuário do container.</strong>
 * É o que faz estes testes exercitarem o privilégio que a aplicação realmente tem: uma
 * migration que precise de mais do que isso falha aqui, e não só no ambiente onde ninguém
 * está olhando. O papel vem do mesmo {@code docker/scripts/01-create-service-roles.sql} que
 * o compose monta — copiar as instruções para cá deixaria os dois divergirem em silêncio.</p>
 *
 * <p>É também por isso que não há {@code @ServiceConnection} aqui: ele deriva usuário e senha
 * do próprio container, e o que se quer é justamente conectar como outro.</p>
 *
 * <p>O nome do banco precisa bater com o do script, que concede privilégio nomeando o banco.
 * O {@code currentSchema} repete o que o {@code application.yml} põe na URL de produção,
 * porque a URL aqui é montada do zero.</p>
 *
 * <p>O container é estático e iniciado no bloco estático, e não pelo {@code @Container} do
 * Testcontainers: assim ele sobe uma vez por JVM e é reaproveitado por todas as classes de
 * teste do módulo, em vez de um container por classe. O encerramento fica com o Ryuk.</p>
 *
 * <p>{@code replace = NONE} é obrigatório porque o {@code @DataJpaTest} substitui o
 * {@code DataSource} por um banco embarcado por padrão — o que anularia o container e
 * testaria um H2 que este projeto não usa em lugar nenhum.</p>
 */
@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class AbstractDatabaseIT {

  protected static final String USUARIO = "user_customer_service";

  protected static final String SENHA = "password";

  private static final Path PAPEIS =
    Path.of("..", "docker", "scripts", "01-create-service-roles.sql").toAbsolutePath().normalize();

  protected static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17-alpine")
    .withDatabaseName("hotel_booking_system")
    .withCopyFileToContainer(
      MountableFile.forHostPath(PAPEIS),
      "/docker-entrypoint-initdb.d/01-create-service-roles.sql"
    )
    .withUrlParam("currentSchema", "customer");

  static {
    DATABASE.start();
  }

  @DynamicPropertySource
  static void configurarDataSource(final DynamicPropertyRegistry registro) {
    registro.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registro.add("spring.datasource.username", () -> USUARIO);
    registro.add("spring.datasource.password", () -> SENHA);
  }

}
