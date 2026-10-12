package com.hotel.booking.system.commons.infrastructure.messaging;

import com.hotel.booking.system.commons.core.domain.event.TrustedEventPackages;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import tools.jackson.databind.json.JsonMapper;

/**
 * O conversor JSON dos eventos entre os serviços: o do Spring AMQP, já com os pacotes
 * confiáveis do contrato, e com a falha de conversão sempre sinalizada pelo tipo certo.
 * <p>
 * O {@link JacksonJsonMessageConverter} recusa um {@code __TypeId__} fora dos pacotes
 * confiáveis com um {@code IllegalArgumentException} cru, e as demais falhas de conversão
 * (JSON malformado, classe inexistente) com {@link MessageConversionException}. Para o retry
 * dos listeners as duas são a mesma coisa — a mensagem não se desserializa, e tentar de novo
 * não muda isso —, mas só a segunda é reconhecível como tal. Sem esta tradução, a mensagem de
 * um tipo recusado gastaria todas as tentativas antes de chegar à DLQ.
 * <p>
 * Só o que sai do próprio {@code fromMessage} é traduzido. Um {@code IllegalArgumentException}
 * lançado pelo handler, depois da conversão, não passa por aqui e continua sendo uma falha
 * como outra qualquer.
 */
public class EventMessageConverter extends JacksonJsonMessageConverter {

  public EventMessageConverter(final JsonMapper jsonMapper) {
    super(jsonMapper, TrustedEventPackages.names());
  }

  @Override
  public Object fromMessage(final Message message, final Object conversionHint) throws MessageConversionException {
    try {
      return super.fromMessage(message, conversionHint);
    } catch (final IllegalArgumentException e) {
      throw new MessageConversionException("Failed to convert Message content", e);
    }
  }

}
