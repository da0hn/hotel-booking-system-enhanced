package com.hotel.booking.system.commons.core.domain.exception;

/**
 * Um handler recebeu um subtipo de evento que o seu {@code switch} não trata.
 * <p>
 * É uma falha de programação, e não de execução: as hierarquias de evento são {@code sealed},
 * então o {@code default} só é alcançado quando alguém acrescenta um subtipo a {@code permits}
 * e esquece de tratá-lo no handler. Reprocessar a mesma mensagem nunca muda o resultado, e é
 * por isso que o retry dos listeners trata esta exceção como definitiva e a manda direto para
 * a DLQ.
 * <p>
 * Ela vive fora do pacote de eventos de propósito: aquele pacote é varrido pelo
 * {@code EventContractTest} e autorizado em {@code TrustedEventPackages} a instanciar
 * classes vindas do header {@code __TypeId__}, e uma exceção não é evento nem deve ser
 * instanciável por uma mensagem.
 */
public class UnsupportedEventTypeException extends RuntimeException {

  public UnsupportedEventTypeException(final Class<?> eventHierarchy, final Object event) {
    super("Failed on handling sub-type of %s: Unknown event %s"
      .formatted(eventHierarchy.getSimpleName(), event.getClass().getName()));
  }

}
