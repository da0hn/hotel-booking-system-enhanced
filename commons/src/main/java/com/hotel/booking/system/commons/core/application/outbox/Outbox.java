package com.hotel.booking.system.commons.core.application.outbox;

/**
 * Grava um evento para ser publicado depois, na mesma transação de quem chamou.
 *
 * <p>Nada é enviado ao broker aqui. O {@code OutboxRelay} lê a linha, publica com a mesma
 * confirmação de entrega do {@code AbstractRabbitPublisher} e só então marca a linha como
 * publicada. Se a transação de quem gravou desfaz, a linha vai junto.</p>
 */
public interface Outbox {

  /**
   * @param aggregateId chave de ordenação; na saga, o {@code reservationOrderId}
   * @param exchange destino já resolvido por quem conhece a topologia
   * @param routingKey routing key já resolvida
   * @param event evento de domínio; o tipo concreto vira o {@code __TypeId__} na publicação
   */
  void append(String aggregateId, String exchange, String routingKey, Object event);

}
