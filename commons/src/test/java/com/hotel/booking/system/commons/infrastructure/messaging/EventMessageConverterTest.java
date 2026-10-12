package com.hotel.booking.system.commons.infrastructure.messaging;

import com.hotel.booking.system.commons.core.domain.event.PaymentCompletedEvent;
import com.hotel.booking.system.commons.core.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Conversor JSON dos eventos")
class EventMessageConverterTest {

  private final EventMessageConverter converter = new EventMessageConverter(JsonMapper.builder().build());

  @Test
  @DisplayName("converte um evento de ida e volta preservando o tipo no header")
  void converteEventoDeIdaEVolta() {
    final var evento = PaymentCompletedEvent.builder()
      .reservationOrderId("pedido")
      .customerId("customer")
      .totalPrice(new BigDecimal("19.9999"))
      .status(PaymentStatus.COMPLETED)
      .build();

    final var mensagem = this.converter.toMessage(evento, new MessageProperties());

    assertThat(mensagem.getMessageProperties().getHeaders())
      .containsEntry("__TypeId__", PaymentCompletedEvent.class.getName());
    assertThat(this.converter.fromMessage(mensagem))
      .usingRecursiveComparison()
      .ignoringFields("createdAt")
      .isEqualTo(evento);
  }

  @Test
  @DisplayName("sinaliza o tipo fora dos pacotes confiáveis como falha de conversão, e não como IllegalArgumentException")
  void sinalizaTipoNaoConfiavelComoFalhaDeConversao() {
    final var mensagem = this.mensagem("{}", "com.invasor.Gadget");

    assertThatThrownBy(() -> this.converter.fromMessage(mensagem))
      .isInstanceOf(MessageConversionException.class)
      .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("mantém como falha de conversão o JSON malformado")
  void mantemJsonMalformadoComoFalhaDeConversao() {
    final var mensagem = this.mensagem("{isto não é json", PaymentCompletedEvent.class.getName());

    assertThatThrownBy(() -> this.converter.fromMessage(mensagem))
      .isInstanceOf(MessageConversionException.class);
  }

  @Test
  @DisplayName("mantém como falha de conversão a classe de evento inexistente")
  void mantemClasseInexistenteComoFalhaDeConversao() {
    final var mensagem = this.mensagem("{}", PaymentCompletedEvent.class.getPackageName() + ".NaoExiste");

    assertThatThrownBy(() -> this.converter.fromMessage(mensagem))
      .isInstanceOf(MessageConversionException.class);
  }

  private Message mensagem(final String corpo, final String tipo) {
    return MessageBuilder.withBody(corpo.getBytes(UTF_8))
      .setContentType("application/json")
      .setHeader("__TypeId__", tipo)
      .build();
  }

}
