package com.hotel.booking.system.booking.service.data.db;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Base dos testes de integração de persistência deste serviço.
 *
 * <p>O container é o único ponto do módulo que sabe qual banco está por baixo. Trocar de
 * engine é trocar esta declaração — as subclasses não mencionam MySQL nem PostgreSQL, e é
 * por isso que elas provam <em>equivalência</em>: o mesmo teste roda contra os dois.</p>
 *
 * <p>A imagem é a mesma do {@code docker/common.yml}. Um teste contra uma versão diferente
 * da que roda em produção mede o banco errado.</p>
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

  @ServiceConnection
  protected static final MySQLContainer DATABASE = new MySQLContainer("mysql:8.0.33");

  static {
    DATABASE.start();
  }

}
