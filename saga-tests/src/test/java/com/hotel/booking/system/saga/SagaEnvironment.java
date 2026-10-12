package com.hotel.booking.system.saga;

import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * O ambiente completo da saga: um PostgreSQL, um RabbitMQ e os quatro serviços como processos.
 *
 * <p>As imagens são as do {@code docker/common.yml}, e o PostgreSQL recebe o mesmo
 * {@code 01-create-service-roles.sql} que o compose monta. Cada serviço conecta com o
 * próprio usuário e cria o próprio schema pelo Flyway, exatamente como em produção — um
 * ambiente mais permissivo que o real esconderia justamente o tipo de falha que este teste
 * existe para pegar.</p>
 *
 * <p>Os serviços são configurados só por variável de ambiente, a mesma interface do
 * {@code docker/services.yml}. Do que cada {@code application.yml} define, só vai o que muda
 * entre ambientes: onde estão o banco e o broker, e a porta. Usuários, senhas e schemas ficam
 * nos defaults dos arquivos.</p>
 *
 * <p>O {@code payment-service} é o único que se reinicia ao longo da suíte: o sorteio de falha
 * é lido uma vez, no arranque, de {@code FAILURE_CHANCE_PERCENTAGE}. Para aprovar sempre usa-se
 * {@code 0}, para recusar sempre {@code 100} — os dois extremos que não dependem de sorte.
 * Reiniciar não perde mensagens: as filas são duráveis e ficam no broker.</p>
 */
final class SagaEnvironment implements AutoCloseable {

  private static final String DATABASE_NAME = "hotel_booking_system";

  private static final Path ROLES =
    Path.of("..", "docker", "scripts", "01-create-service-roles.sql").toAbsolutePath().normalize();

  private static final Path LOGS = Path.of("target", "saga-logs").toAbsolutePath();

  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

  private final PostgreSQLContainer database = new PostgreSQLContainer("postgres:17-alpine")
    .withDatabaseName(DATABASE_NAME)
    .withCopyFileToContainer(
      MountableFile.forHostPath(ROLES),
      "/docker-entrypoint-initdb.d/01-create-service-roles.sql"
    );

  private final RabbitMQContainer broker = new RabbitMQContainer("rabbitmq:3-management")
    .withAdminUser("root")
    .withAdminPassword("root");

  private ServiceProcess hotel;

  private ServiceProcess booking;

  private ServiceProcess customer;

  private ServiceProcess payment;

  private int paymentFailureChance = -1;

  static SagaEnvironment start() {
    final var environment = new SagaEnvironment();
    try {
      environment.startAll();
    }
    catch (final RuntimeException e) {
      environment.close();
      throw e;
    }
    return environment;
  }

  private static int freePort() {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
    catch (final IOException e) {
      throw new IllegalStateException("Sem porta livre para um serviço", e);
    }
  }

  private void startAll() {
    Startables.deepStart(this.database, this.broker).join();

    final var shared = new HashMap<String, String>();
    shared.put("DB_HOST", this.database.getHost());
    shared.put("DB_PORT", String.valueOf(this.database.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)));
    shared.put("DB_NAME", DATABASE_NAME);
    shared.put("RABBITMQ_HOST", this.broker.getHost());
    shared.put("RABBITMQ_PORT", String.valueOf(this.broker.getAmqpPort()));
    shared.put("RABBITMQ_USERNAME", "root");
    shared.put("RABBITMQ_PASSWORD", "root");

    this.hotel = this.service("hotel-service", "/hotel-service", "HOTEL_SERVER_PORT", shared, Map.of());
    this.booking = this.service("booking-service", "/booking-service", "BOOKING_SERVER_PORT", shared, Map.of());
    this.customer = this.service("customer-service", "/customer-service", "CUSTOMER_SERVER_PORT", shared, Map.of());
    this.paymentFailureChance = 0;
    this.payment = this.paymentService(0);

    final var all = List.of(this.hotel, this.booking, this.customer, this.payment);
    all.forEach(ServiceProcess::start);
    all.forEach(service -> service.awaitHealthy(STARTUP_TIMEOUT));
  }

  private ServiceProcess service(
    final String name,
    final String contextPath,
    final String portVariable,
    final Map<String, String> shared,
    final Map<String, String> extra
  ) {
    final var port = freePort();
    final var environment = new HashMap<>(shared);
    environment.put(portVariable, String.valueOf(port));
    environment.putAll(extra);
    return new ServiceProcess(name, contextPath, port, environment, LOGS);
  }

  private ServiceProcess paymentService(final int failureChance) {
    final var broker = Map.of(
      "RABBITMQ_HOST", this.broker.getHost(),
      "RABBITMQ_PORT", String.valueOf(this.broker.getAmqpPort()),
      "RABBITMQ_USERNAME", "root",
      "RABBITMQ_PASSWORD", "root"
    );
    return this.service(
      "payment-service",
      "/payment-service",
      "PAYMENT_SERVER_PORT",
      broker,
      Map.of("FAILURE_CHANCE_PERCENTAGE", String.valueOf(failureChance))
    );
  }

  /**
   * Garante que o pagamento aprova sempre ({@code 0}) ou recusa sempre ({@code 100}). Reinicia o
   * {@code payment-service} só quando o valor muda.
   */
  void usePaymentFailureChance(final int failureChance) {
    if (this.paymentFailureChance == failureChance) {
      return;
    }
    this.payment.stop();
    this.payment = this.paymentService(failureChance);
    this.payment.start();
    this.payment.awaitHealthy(STARTUP_TIMEOUT);
    this.paymentFailureChance = failureChance;
  }

  String hotelUrl() {
    return this.hotel.baseUrl();
  }

  String customerUrl() {
    return this.customer.baseUrl();
  }

  /**
   * Conexão como dono do container, e não como usuário de serviço. É deliberado: o teste lê o
   * estado dos três schemas para conferir o resultado e semeia um quarto, e nenhum usuário de
   * serviço enxerga o schema do outro.
   */
  Connection openConnection() throws SQLException {
    return DriverManager.getConnection(
      this.database.getJdbcUrl(),
      this.database.getUsername(),
      this.database.getPassword()
    );
  }

  /**
   * Final do log de cada serviço, para anexar a uma falha de teste.
   */
  String diagnostics() {
    return Stream.of(this.hotel, this.booking, this.customer, this.payment)
      .filter(service -> service != null)
      .map(service -> "===== %s =====%n%s".formatted(service.name(), service.tail(40)))
      .reduce("", (left, right) -> left + System.lineSeparator() + right);
  }

  @Override
  public void close() {
    Stream.of(this.hotel, this.booking, this.customer, this.payment)
      .filter(service -> service != null)
      .forEach(ServiceProcess::stop);
    this.broker.stop();
    this.database.stop();
  }

}
