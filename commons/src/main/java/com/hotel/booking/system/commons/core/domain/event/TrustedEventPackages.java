package com.hotel.booking.system.commons.core.domain.event;

/**
 * Os pacotes cujas classes podem chegar pelo header {@code __TypeId__} e ser
 * instanciadas na desserialização de uma mensagem.
 * <p>
 * A lista existe porque o {@code JacksonJsonMessageConverter} do Spring AMQP 4
 * inverteu o default do antecessor: o {@code Jackson2JsonMessageConverter}
 * nascia confiando em qualquer pacote, e este nasce confiando apenas em
 * {@code java.util} e {@code java.lang}. Sem a declaração explícita, todo
 * evento deste módulo é recusado com {@code IllegalArgumentException} no
 * listener — e o sintoma não é uma falha de arranque, é a saga parando na
 * primeira resposta, com os quatro serviços saudáveis.
 * <p>
 * O casamento é por igualdade de pacote, e não por prefixo: um subpacote novo
 * de evento precisa ser acrescentado aqui, ou as mensagens dele serão
 * recusadas. Ela vive no {@code commons} porque é aqui que o contrato de
 * eventos é definido — os quatro serviços apenas o consomem.
 */
public final class TrustedEventPackages {

  private static final String[] NAMES = {
    "com.hotel.booking.system.commons.core.domain.event",
    "com.hotel.booking.system.commons.core.domain.event.customer"
  };

  private TrustedEventPackages() {
  }

  public static String[] names() {
    return NAMES.clone();
  }

}
