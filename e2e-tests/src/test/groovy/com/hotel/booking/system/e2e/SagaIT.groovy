package com.hotel.booking.system.e2e

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.sql.Sql
import org.spockframework.runtime.SpockTimeoutError
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Title
import spock.util.concurrent.PollingConditions

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
 * <p>Os cenários rodam na ordem em que estão declarados — o Spock faz isso por padrão — porque o
 * {@code payment-service} sorteia o resultado no arranque: os dois primeiros usam {@code 0}
 * (sempre aprova) e o último {@code 100} (sempre recusa), o que custa um único reinício. Não há
 * {@code @Stepwise} de propósito: cada cenário usa um quarto próprio, então nenhum depende do
 * estado deixado pelo anterior, e se o primeiro falhar os outros dois ainda dizem se o resto da
 * saga funciona.</p>
 *
 * <p>Os dados vêm das migrations de seed. A exceção é o quarto de uma unidade só do cenário de
 * indisponibilidade, que o teste cria, porque nenhum quarto semeado permite esgotar o estoque
 * com uma reserva.</p>
 */
@Title("Saga de reserva ponta a ponta")
class SagaIT extends Specification {

  static final String CUSTOMER_ID = 'f1e28a47-8852-45e8-b8e1-e6701633dd56'

  static final String HOTEL_ID = 'b69b768f-32d8-4c70-a3ef-b5ace438c5e7'

  static final String KING_ROOM_ID = 'e86ff4c5-6f20-419e-8ce8-3140db9bd7a7'

  static final String DELUXE_ROOM_ID = 'ebf84e50-6aa8-461b-837d-f3a541015595'

  static final String CHECK_IN = '10/01/2030'

  static final String CHECK_OUT = '13/01/2030'

  static final double SAGA_TIMEOUT_SECONDS = 90

  static final HttpClient HTTP = HttpClient.newHttpClient()

  @Shared
  E2eEnvironment environment

  def setupSpec() {
    environment = E2eEnvironment.start()
  }

  def cleanupSpec() {
    environment?.close()
  }

  def "pagamento aprovado confirma a reserva e fecha a timeline em RESERVED"() {
    given:
    environment.usePaymentFailureChance(0)

    when:
    def reservationOrderId = bookRoom(KING_ROOM_ID)
    def order = awaitStatus(reservationOrderId, 'RESERVED')

    then:
    chronologicalStatuses(order) == ['AWAITING_RESERVATION', 'AWAITING_PAYMENT', 'PAYMENT_CONFIRMED', 'RESERVED']
    order.hotelId == HOTEL_ID
    order.totalPrice == 342
    awaitScalar('select status from booking.booking where reservation_order_id = ?', reservationOrderId, 'CONFIRMED')
    awaitScalar('select current_status from customer.reservation_order where id = ?', reservationOrderId, 'RESERVED')
  }

  def "reserva do último quarto já ocupado falha sem gravar a reserva"() {
    given:
    environment.usePaymentFailureChance(0)
    def singleUnitRoomId = seedSingleUnitRoom()

    when:
    def first = bookRoom(singleUnitRoomId)
    awaitStatus(first, 'RESERVED')
    def second = bookRoom(singleUnitRoomId)
    def order = awaitStatus(second, 'RESERVATION_FAILED')

    then:
    chronologicalStatuses(order) == ['AWAITING_RESERVATION', 'RESERVATION_FAILED']
    failureReasons(order).size() == 1
    failureReasons(order).first().contains("is no longer available for period ${CHECK_IN} - ${CHECK_OUT}")
    scalar('select count(*) from booking.booking where reservation_order_id = ?', second) == '0'
    awaitScalar('select status from booking.booking where reservation_order_id = ?', first, 'CONFIRMED')
  }

  def "pagamento recusado cancela a reserva e fecha a timeline em PAYMENT_FAILED"() {
    given:
    environment.usePaymentFailureChance(100)

    when:
    def reservationOrderId = bookRoom(DELUXE_ROOM_ID)
    def order = awaitStatus(reservationOrderId, 'PAYMENT_FAILED')

    then:
    chronologicalStatuses(order) == ['AWAITING_RESERVATION', 'AWAITING_PAYMENT', 'PAYMENT_FAILED']
    failureReasons(order).size() == 1
    awaitScalar('select status from booking.booking where reservation_order_id = ?', reservationOrderId, 'CANCELED')
    awaitScalar('select current_status from customer.reservation_order where id = ?', reservationOrderId, 'PAYMENT_FAILED')
  }

  private String bookRoom(String roomId) {
    def body = JsonOutput.toJson([
      customerId: CUSTOMER_ID,
      hotelId   : HOTEL_ID,
      guests    : 2,
      checkIn   : CHECK_IN,
      checkOut  : CHECK_OUT,
      rooms     : [[roomId: roomId, roomQuantity: 1]]
    ])
    def request = HttpRequest.newBuilder(URI.create("${environment.hotelUrl()}/hotel/booking"))
      .timeout(Duration.ofSeconds(10))
      .header('Content-Type', 'application/json')
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    def response = HTTP.send(request, HttpResponse.BodyHandlers.ofString())

    assert response.statusCode() == 200: "POST /hotel/booking: ${response.body()}"
    new JsonSlurper().parseText(response.body()).data.reservationOrderId as String
  }

  /**
   * Consulta o pedido até ele chegar ao status esperado. Enquanto o evento inicial não chegou ao
   * {@code customer-service}, o {@code GET} não responde 200 — tratado como "ainda não".
   */
  private Map awaitStatus(String reservationOrderId, String status) {
    Map order = null
    try {
      // O Spock trata cada comando dentro de `eventually` como condição e recusa uma atribuição
      // solta; ela fica dentro do `assert` para guardar o último estado visto.
      new PollingConditions(timeout: SAGA_TIMEOUT_SECONDS, delay: 0.5, initialDelay: 0).eventually {
        assert (order = fetchOrder(reservationOrderId))?.status == status
      }
    }
    catch (SpockTimeoutError e) {
      def lastSeen = order == null ? '(pedido ainda não existe no customer-service)' : chronologicalStatuses(order)
      throw new AssertionError(
        "O pedido ${reservationOrderId} não chegou a ${status} em ${SAGA_TIMEOUT_SECONDS} s. " +
          "Último estado visto: ${lastSeen}\n${environment.diagnostics()}".toString(),
        e
      )
    }
    order
  }

  private Map fetchOrder(String reservationOrderId) {
    def request = HttpRequest.newBuilder(URI.create(
      "${environment.customerUrl()}/customers/${CUSTOMER_ID}/reservation-order/${reservationOrderId}"
    ))
      .timeout(Duration.ofSeconds(10))
      .GET()
      .build()
    def response = HTTP.send(request, HttpResponse.BodyHandlers.ofString())
    response.statusCode() == 200 ? new JsonSlurper().parseText(response.body()).data as Map : null
  }

  /**
   * O endpoint devolve a timeline do mais recente para o mais antigo; a ordem natural de leitura
   * de uma saga é a inversa.
   */
  private static List<String> chronologicalStatuses(Map order) {
    order.timeline*.status.reverse()
  }

  private static List<String> failureReasons(Map order) {
    order.timeline*.failureReason.findAll { it != null && !it.toString().isBlank() }
  }

  private String scalar(String sql, String parameter) {
    withSql { Sql db -> db.firstRow(sql, [parameter])?.getAt(0)?.toString() }
  }

  /**
   * O status do booking e o do pedido são gravados em transações de serviços diferentes e a
   * leitura do estado final não é atômica entre eles: espera-se o banco convergir em vez de
   * assumir que ele já convergiu quando o último evento chegou.
   */
  private void awaitScalar(String sql, String parameter, String expected) {
    try {
      new PollingConditions(timeout: 15, delay: 0.25, initialDelay: 0).eventually {
        assert scalar(sql, parameter) == expected
      }
    }
    catch (SpockTimeoutError e) {
      throw new AssertionError("O banco não convergiu para ${expected} em: ${sql}".toString(), e)
    }
  }

  /**
   * Cria um quarto de uma unidade só nos dois bancos que o conhecem. Hotel e booking guardam
   * réplicas separadas do quarto — a duplicação que a issue #4 pretende remover —, então o
   * teste semeia as duas.
   */
  private String seedSingleUnitRoom() {
    def roomId = UUID.randomUUID().toString()
    withSql { Sql db ->
      db.execute(
        'insert into hotel.room (id, name, description, capacity, current_price, hotel_id, quantity) values (?, ?, ?, 2, 100, ?, 1)',
        [roomId, 'Quarto SagaIT', 'Quarto de uma unidade criado pelo teste da saga', HOTEL_ID]
      )
      db.execute(
        'insert into booking.room (id, hotel_id, current_price, capacity, quantity) values (?, ?, 100, 2, 1)',
        [roomId, HOTEL_ID]
      )
    }
    roomId
  }

  /**
   * Conexão como dono do container (ver {@code E2eEnvironment.openConnection}), fechada ao fim.
   * O {@code Sql} criado sobre uma {@code Connection} não a fecha sozinho.
   */
  private <T> T withSql(Closure<T> work) {
    environment.openConnection().withCloseable { connection -> work(new Sql(connection)) }
  }

}
