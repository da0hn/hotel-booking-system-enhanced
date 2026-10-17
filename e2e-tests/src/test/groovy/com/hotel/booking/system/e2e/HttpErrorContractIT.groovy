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
 * <p>O 500 por exceção inesperada não aparece aqui: provocá-lo exigiria derrubar o banco, e o
 * que importa nele, a mensagem genérica e o stacktrace só no log, está no
 * {@code GlobalExceptionHandlerTest}.</p>
 */
@Title("Contrato HTTP de erro dos serviços com API")
class HttpErrorContractIT extends Specification {

  static final String CUSTOMER_ID = 'f1e28a47-8852-45e8-b8e1-e6701633dd56'

  static final String HOTEL_ID = 'b69b768f-32d8-4c70-a3ef-b5ace438c5e7'

  static final String KING_ROOM_ID = 'e86ff4c5-6f20-419e-8ce8-3140db9bd7a7'

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

  def "rota inexistente continua respondendo 404, e não 500, agora com o envelope"() {
    when:
    def response = get("${environment.hotelUrl()}/actuator/env")

    then:
    response.statusCode() == 404
    isErrorEnvelope(response, 'request.invalid')
  }

  private boolean isErrorEnvelope(HttpResponse<String> response, String message) {
    def body = new JsonSlurper().parseText(response.body()) as Map
    body.success == false &&
      body.message == message &&
      body.data == null &&
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
