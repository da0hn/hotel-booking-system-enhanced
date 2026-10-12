package com.hotel.booking.system.saga;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A saga de reserva de ponta a ponta: o {@code POST /hotel/booking} entra pelo
 * {@code hotel-service} e o resultado é lido no {@code customer-service} e nos bancos, depois de
 * passar por RabbitMQ, {@code booking-service} e {@code payment-service} de verdade.
 *
 * <p>É a rede de segurança das refatorações que mexem no contrato de eventos, na fronteira
 * transacional ou na regra de disponibilidade. Os testes de unidade provam cada handler
 * isolado; o que nenhum deles prova é que as quatro pontas se entendem — um evento movido de
 * pacote, um pacote fora de {@code TrustedEventPackages} ou um listener sem
 * {@code @Transactional} deixam todos os serviços saudáveis e a saga parada, e é aqui que isso
 * aparece.</p>
 *
 * <p>Os cenários rodam em ordem porque o {@code payment-service} sorteia o resultado no arranque:
 * os dois primeiros usam {@code 0} (sempre aprova) e o último {@code 100} (sempre recusa), o que
 * custa um único reinício. Cada cenário usa um quarto próprio, então nenhum depende do estado
 * deixado pelo anterior.</p>
 *
 * <p>Os dados vêm das migrations de seed. A exceção é o quarto de uma unidade só do cenário de
 * indisponibilidade, que o teste cria, porque nenhum quarto semeado permite esgotar o estoque
 * com uma reserva.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Saga de reserva ponta a ponta")
class SagaIT {

  private static final String CUSTOMER_ID = "f1e28a47-8852-45e8-b8e1-e6701633dd56";

  private static final String HOTEL_ID = "b69b768f-32d8-4c70-a3ef-b5ace438c5e7";

  private static final String KING_ROOM_ID = "e86ff4c5-6f20-419e-8ce8-3140db9bd7a7";

  private static final String DELUXE_ROOM_ID = "ebf84e50-6aa8-461b-837d-f3a541015595";

  private static final String CHECK_IN = "10/01/2030";

  private static final String CHECK_OUT = "13/01/2030";

  private static final Duration SAGA_TIMEOUT = Duration.ofSeconds(90);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static SagaEnvironment environment;

  @BeforeAll
  static void startEnvironment() {
    environment = SagaEnvironment.start();
  }

  @AfterAll
  static void stopEnvironment() {
    if (environment != null) {
      environment.close();
    }
  }

  @Test
  @Order(1)
  @DisplayName("pagamento aprovado confirma a reserva e fecha a timeline em RESERVED")
  void pagamentoAprovadoConfirmaAReserva() throws Exception {
    environment.usePaymentFailureChance(0);

    final var reservationOrderId = this.bookRoom(KING_ROOM_ID);
    final var order = this.awaitStatus(reservationOrderId, "RESERVED");

    assertThat(this.chronologicalStatuses(order))
      .containsExactly("AWAITING_RESERVATION", "AWAITING_PAYMENT", "PAYMENT_CONFIRMED", "RESERVED");
    assertThat(order.path("hotelId").asString()).isEqualTo(HOTEL_ID);
    assertThat(order.path("totalPrice").decimalValue()).isEqualByComparingTo("342");
    this.awaitScalar("select status from booking.booking where reservation_order_id = ?", reservationOrderId, "CONFIRMED");
    this.awaitScalar("select current_status from customer.reservation_order where id = ?", reservationOrderId, "RESERVED");
  }

  @Test
  @Order(2)
  @DisplayName("reserva do último quarto já ocupado falha sem gravar a reserva")
  void ultimoQuartoOcupadoFalhaAReserva() throws Exception {
    environment.usePaymentFailureChance(0);
    final var singleUnitRoomId = this.seedSingleUnitRoom();

    final var first = this.bookRoom(singleUnitRoomId);
    this.awaitStatus(first, "RESERVED");
    final var second = this.bookRoom(singleUnitRoomId);
    final var order = this.awaitStatus(second, "RESERVATION_FAILED");

    assertThat(this.chronologicalStatuses(order)).containsExactly("AWAITING_RESERVATION", "RESERVATION_FAILED");
    assertThat(this.failureReasons(order)).singleElement().asString()
      .contains("is no longer available for period " + CHECK_IN + " - " + CHECK_OUT);
    assertThat(this.scalar("select count(*) from booking.booking where reservation_order_id = ?", second)).isEqualTo("0");
    this.awaitScalar("select status from booking.booking where reservation_order_id = ?", first, "CONFIRMED");
  }

  @Test
  @Order(3)
  @DisplayName("pagamento recusado cancela a reserva e fecha a timeline em PAYMENT_FAILED")
  void pagamentoRecusadoCancelaAReserva() throws Exception {
    environment.usePaymentFailureChance(100);

    final var reservationOrderId = this.bookRoom(DELUXE_ROOM_ID);
    final var order = this.awaitStatus(reservationOrderId, "PAYMENT_FAILED");

    assertThat(this.chronologicalStatuses(order))
      .containsExactly("AWAITING_RESERVATION", "AWAITING_PAYMENT", "PAYMENT_FAILED");
    assertThat(this.failureReasons(order)).singleElement().asString().isNotBlank();
    this.awaitScalar("select status from booking.booking where reservation_order_id = ?", reservationOrderId, "CANCELED");
    this.awaitScalar("select current_status from customer.reservation_order where id = ?", reservationOrderId, "PAYMENT_FAILED");
  }

  private String bookRoom(final String roomId) throws Exception {
    final var body = JSON.createObjectNode();
    body.put("customerId", CUSTOMER_ID);
    body.put("hotelId", HOTEL_ID);
    body.put("guests", 2);
    body.put("checkIn", CHECK_IN);
    body.put("checkOut", CHECK_OUT);
    body.putArray("rooms").addObject().put("roomId", roomId).put("roomQuantity", 1);

    final var request = HttpRequest.newBuilder(URI.create(environment.hotelUrl() + "/hotel/booking"))
      .timeout(Duration.ofSeconds(10))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
      .build();
    final var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).as("POST /hotel/booking: %s", response.body()).isEqualTo(200);
    return JSON.readTree(response.body()).path("data").path("reservationOrderId").asString();
  }

  /**
   * Consulta o pedido até ele chegar ao status esperado. Enquanto o evento inicial não chegou ao
   * {@code customer-service}, o {@code GET} não responde 200 — tratado como "ainda não".
   */
  private JsonNode awaitStatus(final String reservationOrderId, final String status) {
    try {
      return await()
        .atMost(SAGA_TIMEOUT)
        .pollInterval(Duration.ofMillis(500))
        .until(
          () -> this.fetchOrder(reservationOrderId),
          order -> order != null && status.equals(order.path("status").asString())
        );
    }
    catch (final ConditionTimeoutException e) {
      throw new AssertionError(
        "O pedido %s não chegou a %s em %s. Último estado visto: %s%n%s".formatted(
          reservationOrderId, status, SAGA_TIMEOUT, this.lastSeen(reservationOrderId), environment.diagnostics()
        ),
        e
      );
    }
  }

  private JsonNode fetchOrder(final String reservationOrderId) throws IOException, InterruptedException {
    final var request = HttpRequest.newBuilder(URI.create(
        "%s/customers/%s/reservation-order/%s".formatted(environment.customerUrl(), CUSTOMER_ID, reservationOrderId)
      ))
      .timeout(Duration.ofSeconds(10))
      .GET()
      .build();
    final var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    return response.statusCode() == 200 ? JSON.readTree(response.body()).path("data") : null;
  }

  private String lastSeen(final String reservationOrderId) {
    try {
      final var order = this.fetchOrder(reservationOrderId);
      return order == null ? "(pedido ainda não existe no customer-service)" : this.chronologicalStatuses(order).toString();
    }
    catch (final IOException e) {
      return "(consulta falhou: " + e.getMessage() + ")";
    }
    catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return "(consulta interrompida)";
    }
  }

  /**
   * O endpoint devolve a timeline do mais recente para o mais antigo; a ordem natural de leitura
   * de uma saga é a inversa.
   */
  private List<String> chronologicalStatuses(final JsonNode order) {
    final var statuses = new ArrayList<String>();
    for (final var item : order.path("timeline")) {
      statuses.add(item.path("status").asString());
    }
    Collections.reverse(statuses);
    return statuses;
  }

  private List<String> failureReasons(final JsonNode order) {
    final var reasons = new ArrayList<String>();
    for (final var item : order.path("timeline")) {
      final var reason = item.path("failureReason");
      if (!reason.isNull() && !reason.isMissingNode() && !reason.asString().isBlank()) {
        reasons.add(reason.asString());
      }
    }
    return reasons;
  }

  private String scalar(final String sql, final String parameter) throws SQLException {
    try (var connection = environment.openConnection(); var statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (var rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  /**
   * O status do booking e o do pedido são gravados em transações de serviços diferentes e a
   * leitura do estado final não é atômica entre eles: espera-se o banco convergir em vez de
   * assumir que ele já convergiu quando o último evento chegou.
   */
  private void awaitScalar(final String sql, final String parameter, final String expected) {
    try {
      await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250))
        .untilAsserted(() -> assertThat(this.scalar(sql, parameter)).isEqualTo(expected));
    }
    catch (final ConditionTimeoutException e) {
      throw new AssertionError("O banco não convergiu para %s em: %s".formatted(expected, sql), e);
    }
  }

  /**
   * Cria um quarto de uma unidade só nos dois bancos que o conhecem. Hotel e booking guardam
   * réplicas separadas do quarto — a duplicação que a issue #4 pretende remover —, então o
   * teste semeia as duas.
   */
  private String seedSingleUnitRoom() throws SQLException {
    final var roomId = UUID.randomUUID().toString();
    try (var connection = environment.openConnection()) {
      try (var statement = connection.prepareStatement(
        "insert into hotel.room (id, name, description, capacity, current_price, hotel_id, quantity) values (?, ?, ?, 2, 100, ?, 1)"
      )) {
        statement.setString(1, roomId);
        statement.setString(2, "Quarto SagaIT");
        statement.setString(3, "Quarto de uma unidade criado pelo teste da saga");
        statement.setString(4, HOTEL_ID);
        statement.executeUpdate();
      }
      try (var statement = connection.prepareStatement(
        "insert into booking.room (id, hotel_id, current_price, capacity, quantity) values (?, ?, 100, 2, 1)"
      )) {
        statement.setString(1, roomId);
        statement.setString(2, HOTEL_ID);
        statement.executeUpdate();
      }
    }
    return roomId;
  }

}
