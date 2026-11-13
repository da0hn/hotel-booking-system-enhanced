package com.hotel.booking.system.e2e

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Title

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * O contrato HTTP de erro do {@code hotel-service} e do {@code customer-service}, visto de fora e
 * nos jars de verdade (issue #9).
 *
 * <p>Antes dele, nenhuma exceção de domínio era traduzida: a ordem de reserva inexistente, a
 * regra de capacidade violada e o corpo malformado saíam como 500 do Whitelabel do Spring. Os
 * testes de MockMvc de cada serviço provam o mapeamento; este prova o que eles não enxergam,
 * que é o advice registrado no contexto de verdade e o corpo serializado pelo Jackson de
 * produção, com o {@code time} no formato do envelope.</p>
 *
 * <p>Cobre também a fronteira da busca paginada, {@code GET /hotel?page=&size=} (issue #43): o
 * corpo da página e o {@code 400} de {@code page} ou {@code size} inválido. É o único teste que
 * serializa o {@code page} com o Jackson de produção e avalia o {@code @Min}/{@code @Max} do
 * parâmetro com o validador do contexto real.</p>
 *
 * <p>O 500 por exceção inesperada não aparece aqui: provocá-lo exigiria derrubar o banco, e o
 * que importa nele, a mensagem genérica e o stacktrace só no log, está no
 * {@code GlobalExceptionHandlerTest}.</p>
 */
@Title("Contrato HTTP dos serviços com API")
class HttpErrorContractIT extends Specification {

  static final String CUSTOMER_ID = 'f1e28a47-8852-45e8-b8e1-e6701633dd56'

  static final String HOTEL_ID = 'b69b768f-32d8-4c70-a3ef-b5ace438c5e7'

  static final String KING_ROOM_ID = 'e86ff4c5-6f20-419e-8ce8-3140db9bd7a7'

  static final int SEEDED_HOTELS = 5

  /**
   * Os nomes do seed da V010 em ordem alfabética. A migration insere a "Villa Guiamares" antes da
   * "Canto dos Pássaros", então sem o {@code ORDER BY} a leitura sequencial devolveria as duas
   * trocadas.
   */
  static final List<String> SEEDED_HOTEL_NAMES = [
    'Amazon Plaza Hotel',
    'Getúlio Hotel',
    'Hotel Taiamã',
    'Pousada Canto dos Pássaros',
    'Pousada Villa Guiamares'
  ]

  static final HttpClient HTTP = HttpClient.newHttpClient()

  @Shared
  E2eEnvironment environment

  def setupSpec() {
    environment = E2eEnvironment.start()
  }

  def cleanupSpec() {
    environment?.close()
  }

  def "ordem de reserva inexistente responde 404 com o envelope de erro"() {
    when:
    def response = get("${environment.customerUrl()}/customers/${CUSTOMER_ID}/reservation-order/${UUID.randomUUID()}")

    then:
    response.statusCode() == 404
    isErrorEnvelope(response, 'customer.reservation-order.not.found')
  }

  def "cliente inexistente responde 404 com o envelope de erro"() {
    when:
    def response = get("${environment.customerUrl()}/customers/${UUID.randomUUID()}/reservation-order/${UUID.randomUUID()}")

    then:
    response.statusCode() == 404
    isErrorEnvelope(response, 'customer.not.found')
  }

  def "hóspedes acima da capacidade respondem 422 com a chave de negócio"() {
    when:
    def response = bookRoom(KING_ROOM_ID, 1000)

    then:
    response.statusCode() == 422
    isErrorEnvelope(response, 'hotel.booking.guests.exceeded')
  }

  def "quarto inexistente responde 422, e não 404, porque o quarto vem no corpo da requisição"() {
    when:
    def response = bookRoom(UUID.randomUUID().toString(), 1)

    then:
    response.statusCode() == 422
    isErrorEnvelope(response, 'hotel.room.not.found')
  }

  def "corpo malformado responde 400 com o envelope de erro"() {
    when:
    def response = post("${environment.hotelUrl()}/hotel/booking", '{')

    then:
    response.statusCode() == 400
    isErrorEnvelope(response, 'request.invalid')
  }

  def "id do path que não é UUID responde 400 com request.id.invalid e só o primeiro campo recusado (issue #51)"() {
    when:
    def response = get("${environment.customerUrl()}/customers/${customerId}/reservation-order/${reservationOrderId}")

    then:
    isIdInvalidEnvelope(response, field)
    !response.body().contains('not-a-uuid')

    where:
    customerId                | reservationOrderId        | field
    'not-a-uuid'              | UUID.randomUUID()         | 'customerId'
    UUID.randomUUID()         | 'not-a-uuid'              | 'reservationOrderId'
    'not-a-uuid'              | 'not-a-uuid'              | 'customerId'
  }

  def "quarto do corpo que não é UUID responde 400, e não 500 (issue #51)"() {
    when:
    def response = bookRoom('not-a-uuid', 1)

    then:
    isIdInvalidEnvelope(response, 'rooms[].roomId')
  }

  def "cliente ou hotel do corpo da reserva que não é UUID responde 400, e não 200 com a mensagem na DLQ (issue #52)"() {
    when:
    def response = post("${environment.hotelUrl()}/hotel/booking", JsonOutput.toJson([
      customerId: customerId,
      hotelId   : hotelId,
      guests    : 1,
      checkIn   : '10/01/2030',
      checkOut  : '13/01/2030',
      rooms     : [[roomId: KING_ROOM_ID, roomQuantity: 1]]
    ]))

    then:
    isIdInvalidEnvelope(response, field)
    !response.body().contains('not-a-uuid')

    where:
    customerId    | hotelId       | field
    'not-a-uuid'  | HOTEL_ID      | 'customerId'
    CUSTOMER_ID   | 'not-a-uuid'  | 'hotelId'
    'not-a-uuid'  | 'not-a-uuid'  | 'customerId'
  }

  def "categoria ou localidade do cadastro de hotel que não é UUID responde 400 (issue #51)"() {
    when:
    def response = post("${environment.hotelUrl()}/hotel", JsonOutput.toJson([
      name       : 'Pousada Portal',
      description: 'Pousada no centro da cidade',
      categoryId : categoryId,
      localityId : localityId,
      street     : 'Av Perimetral, 766',
      cep        : '78195-000',
      rooms      : []
    ]))

    then:
    isIdInvalidEnvelope(response, field)

    where:
    categoryId        | localityId        | field
    'not-a-uuid'      | UUID.randomUUID() | 'categoryId'
    UUID.randomUUID() | 'not-a-uuid'      | 'localityId'
  }

  def "rota inexistente continua respondendo 404, e não 500, agora com o envelope"() {
    when:
    def response = get("${environment.hotelUrl()}/actuator/env")

    then:
    response.statusCode() == 404
    isErrorEnvelope(response, 'request.invalid')
  }

  def "a busca de hotéis sem page nem size responde a lista de sempre, sem page"() {
    when:
    def response = get("${environment.hotelUrl()}/hotel")
    def body = new JsonSlurper().parseText(response.body()) as Map

    then:
    response.statusCode() == 200
    body.keySet() == ['success', 'data'] as Set
    body.success == true
    (body.data as List).size() == SEEDED_HOTELS
  }

  def "a busca de hotéis com size responde uma página sem contagem total, e hasNext só é falso na última"() {
    when:
    def first = hotelSearch('size=2')
    def last = hotelSearch('page=2&size=2')

    then:
    first.status == 200
    first.body.data.size() == 2
    first.body.page == [number: 0, size: 2, hasNext: true]
    last.status == 200
    last.body.data.size() == 1
    last.body.page == [number: 2, size: 2, hasNext: false]
  }

  def "a página além do fim responde 200 com data vazio, e não 404"() {
    when:
    def beyond = hotelSearch('page=9')

    then:
    beyond.status == 200
    beyond.body.success == true
    beyond.body.data == []
    beyond.body.page == [number: 9, size: 20, hasNext: false]
  }

  def "a página cujo offset estoura o int responde 200 com data vazio, e não 500"() {
    when:
    def huge = hotelSearch('page=21474837&size=100')
    def maxPage = hotelSearch('page=2147483647')

    then:
    huge.status == 200
    huge.body.data == []
    huge.body.page == [number: 21474837, size: 100, hasNext: false]
    maxPage.status == 200
    maxPage.body.data == []
    maxPage.body.page == [number: 2147483647, size: 20, hasNext: false]
  }

  def "a busca de hotéis lista as páginas por nome, sem repetir nem omitir"() {
    when:
    def names = (0..2).collectMany { hotelSearch("page=${it}&size=2").body.data*.name }

    then:
    names == SEEDED_HOTEL_NAMES
  }

  def "#parameter=#value responde 400 com o campo e a chave da restrição"() {
    when:
    def response = get("${environment.hotelUrl()}/hotel?${parameter}=${value}")
    def body = new JsonSlurper().parseText(response.body()) as Map

    then:
    response.statusCode() == 400
    body.success == false
    body.message == 'request.invalid'
    body.data == [[field: parameter, message: message]]
    (body.time as String) ==~ /\d{2}\/\d{2}\/\d{4} \d{2}:\d{2}:\d{2}/

    where:
    parameter | value | message
    'page'    | '-1'  | 'page.number.invalid'
    'page'    | ''    | 'page.number.invalid'
    'size'    | '0'   | 'page.size.invalid'
    'size'    | '101' | 'page.size.invalid'
    'size'    | ''    | 'page.size.invalid'
  }

  def "page que não é um inteiro responde 400 com o envelope de erro"() {
    when:
    def response = get("${environment.hotelUrl()}/hotel?page=abc")

    then:
    response.statusCode() == 400
    isErrorEnvelope(response, 'request.invalid')
  }

  private Map hotelSearch(String query) {
    def response = get("${environment.hotelUrl()}/hotel?${query}")
    [status: response.statusCode(), body: new JsonSlurper().parseText(response.body()) as Map]
  }

  private boolean isErrorEnvelope(HttpResponse<String> response, String message) {
    def body = new JsonSlurper().parseText(response.body()) as Map
    body.success == false &&
      body.message == message &&
      body.data == null &&
      (body.time as String) ==~ /\d{2}\/\d{2}\/\d{4} \d{2}:\d{2}:\d{2}/
  }

  private boolean isIdInvalidEnvelope(HttpResponse<String> response, String field) {
    def body = new JsonSlurper().parseText(response.body()) as Map
    response.statusCode() == 400 &&
      body.success == false &&
      body.message == 'request.id.invalid' &&
      body.data == [[field: field, message: 'request.id.invalid']] &&
      (body.time as String) ==~ /\d{2}\/\d{2}\/\d{4} \d{2}:\d{2}:\d{2}/
  }

  private HttpResponse<String> bookRoom(String roomId, int guests) {
    post("${environment.hotelUrl()}/hotel/booking", JsonOutput.toJson([
      customerId: CUSTOMER_ID,
      hotelId   : HOTEL_ID,
      guests    : guests,
      checkIn   : '10/01/2030',
      checkOut  : '13/01/2030',
      rooms     : [[roomId: roomId, roomQuantity: 1]]
    ]))
  }

  private HttpResponse<String> get(String url) {
    HTTP.send(
      HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )
  }

  private HttpResponse<String> post(String url, String body) {
    HTTP.send(
      HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header('Content-Type', 'application/json')
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
  }

}
