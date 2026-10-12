package com.hotel.booking.system.e2e

import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Title

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * O que o Actuator deixa ver por fora de cada um dos quatro serviços, nos jars de verdade e
 * pela porta que o compose publica (issue #12).
 *
 * <p>Com {@code include: "*"}, qualquer um que alcançasse a porta lia {@code /actuator/env}
 * (variáveis de ambiente, com as senhas do banco e do RabbitMQ), {@code /actuator/configprops},
 * {@code /actuator/beans}, {@code /actuator/mappings} e {@code /actuator/threaddump}. O alvo é
 * expor só {@code health}, sem detalhes, e {@code info}: tudo que sai disso responde 404.</p>
 *
 * <p>O Actuator é dependência de todos os módulos pelo pom raiz, então o mesmo caminho que
 * respondia 200 passa a responder 404 só por causa da exposição — foi isso que o vermelho
 * antes da correção mostrou, caminho a caminho. A exceção é o {@code heapdump}: no Spring Boot
 * 4.1.1 ele tem {@code defaultAccess = NONE}, então nem o {@code *} o expunha e a issue
 * descreve um vazamento que esta versão já não tinha. Fica na lista como guarda: se alguém
 * ligar o acesso ({@code management.endpoint.heapdump.access}) e expuser o endpoint, é este
 * teste que acusa.</p>
 *
 * <p>Há também um guarda barato de cada serviço, o {@code ActuatorExposureConfigTest}, que lê
 * o {@code application.yml} e roda em todo {@code mvn verify}. Este aqui é o que prova o
 * comportamento de fora, mas só roda com o label {@code e2e} no pull request.</p>
 */
@Title("Exposição do Actuator nos quatro serviços")
class ActuatorExposureIT extends Specification {

  static final List<String> SERVICES = ['hotel-service', 'booking-service', 'customer-service', 'payment-service']

  static final List<String> DENIED_ENDPOINTS = [
    'env', 'configprops', 'beans', 'mappings', 'heapdump', 'threaddump', 'metrics', 'loggers'
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

  /**
   * Não compara o corpo inteiro com {@code {"status":"UP"}}: o Spring Boot 4 acrescenta
   * {@code groups} ({@code ["liveness","readiness"]}), que só nomeia os grupos de health e não
   * diz nada do estado das dependências. O que vazaria é {@code components}/{@code details},
   * que trazem banco, broker e espaço em disco.
   */
  def "#service responde UP em /actuator/health sem componentes nem detalhes"() {
    when:
    def response = get(service, '/actuator/health')
    def body = new JsonSlurper().parseText(response.body()) as Map

    then:
    response.statusCode() == 200
    body.status == 'UP'
    !body.containsKey('components')
    !body.containsKey('details')

    where:
    service << SERVICES
  }

  def "#service responde 200 em /actuator/info"() {
    expect:
    statusOf(service, '/actuator/info') == 200

    where:
    service << SERVICES
  }

  def "#service não expõe /actuator/#endpoint"() {
    expect:
    statusOf(service, "/actuator/${endpoint}") == 404

    where:
    [service, endpoint] << [SERVICES, DENIED_ENDPOINTS].combinations()
  }

  private HttpResponse<String> get(String service, String path) {
    HTTP.send(request(service, path), HttpResponse.BodyHandlers.ofString())
  }

  /**
   * Só o status, descartando o corpo: com o {@code heapdump} exposto o serviço devolve um dump
   * de memória inteiro, que não há por que carregar como texto na JVM do teste.
   */
  private int statusOf(String service, String path) {
    HTTP.send(request(service, path), HttpResponse.BodyHandlers.discarding()).statusCode()
  }

  private HttpRequest request(String service, String path) {
    HttpRequest.newBuilder(URI.create(environment.serviceUrls()[service] + path))
      .timeout(Duration.ofSeconds(60))
      .GET()
      .build()
  }

}
