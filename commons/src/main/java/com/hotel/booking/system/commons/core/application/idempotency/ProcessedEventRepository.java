package com.hotel.booking.system.commons.core.application.idempotency;

import java.time.Instant;

/**
 * Porta do registro de eventos já processados por um serviço.
 * <p>
 * O registro e o efeito do evento precisam ser <strong>atômicos</strong>, ou a deduplicação não
 * é confiável: se o efeito é gravado e o registro se perde, a reentrega repete o efeito; se o
 * registro é gravado e o efeito se perde, a reentrega descarta um evento que nunca foi aplicado.
 * Nos serviços com banco isso se obtém registrando <em>dentro da transação do listener</em>:
 * o commit leva os dois, o rollback desfaz os dois.
 */
public interface ProcessedEventRepository {

  /**
   * Registra o evento como processado.
   *
   * @return {@code true} se o evento é novo e acaba de ser registrado; {@code false} se já havia
   * sido registrado antes — uma reentrega, que o chamador deve descartar.
   */
  boolean register(String eventId, String eventType);

  /**
   * Desfaz o registro de um evento cujo processamento falhou.
   * <p>
   * Só importa para a implementação que não participa de uma transação. Na do banco o rollback
   * já desfaz o registro, e executar um {@code DELETE} depois de uma falha de SQL falharia de
   * novo, numa transação já abortada, escondendo a exceção original — por isso o default é não
   * fazer nada.
   */
  default void release(final String eventId) {
  }

  /**
   * Remove os registros feitos antes de {@code threshold}. O registro só precisa durar mais do
   * que a janela em que o broker ainda pode reentregar a mensagem.
   *
   * @return quantos registros foram removidos.
   */
  int deleteRegisteredBefore(Instant threshold);

}
