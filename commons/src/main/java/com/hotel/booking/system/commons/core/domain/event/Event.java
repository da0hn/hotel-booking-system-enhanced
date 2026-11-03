package com.hotel.booking.system.commons.core.domain.event;

import java.util.UUID;

/**
 * Raiz do contrato de eventos entre os serviços.
 * <p>
 * Todo evento carrega um {@code eventId} gerado na origem, no momento da construção. É ele que
 * permite ao consumidor reconhecer uma reentrega: o RabbitMQ entrega ao menos uma vez, e a mesma
 * mensagem pode chegar duas por ack perdido, reconexão ou redeploy durante o processamento.
 * <p>
 * Cada classe de evento declara o campo com {@code @Builder.Default} e o valor de
 * {@link #newEventId()}. O default no builder, e não um inicializador solto, é o que faz o campo
 * entrar no construtor e no JSON: um {@code final} inicializado fora do construtor seria regerado
 * a cada desserialização, e cada entrega pareceria um evento novo. A mensagem legada, sem o
 * campo, recebe um identificador na leitura — é processada, mas sem deduplicação por
 * {@code eventId}.
 * <p>
 * O default só vale quando o Jackson passa pelo builder: com {@code @Builder} e
 * {@code @AllArgsConstructor} sem {@code @Jacksonized} ele chama o construtor e entrega
 * {@code null} para o campo ausente. Por isso toda classe de evento com {@code @Builder} ou
 * {@code @SuperBuilder} leva {@code @Jacksonized}, e o {@code EventContractTest} prova o
 * comportamento nos dois casos.
 */
public interface Event {

  /**
   * Identificador único do evento, estável entre a publicação e qualquer entrega dela.
   */
  String getEventId();

  /**
   * Gera o identificador de um evento novo. Fica aqui para que o formato (UUID textual, 36
   * caracteres, o que a coluna {@code processed_event.event_id} comporta) tenha um só dono.
   */
  static String newEventId() {
    return UUID.randomUUID().toString();
  }

}
