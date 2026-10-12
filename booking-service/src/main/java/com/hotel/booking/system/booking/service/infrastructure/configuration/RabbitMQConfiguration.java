package com.hotel.booking.system.booking.service.infrastructure.configuration;

import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.QueueProperties;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.RoutingKeyProperties;
import com.hotel.booking.system.commons.infrastructure.messaging.DeadLetterRetryConfiguration;
import com.hotel.booking.system.commons.infrastructure.messaging.DeadLetterTopology;
import com.hotel.booking.system.commons.infrastructure.messaging.EventMessageConverter;
import lombok.AllArgsConstructor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@Import(DeadLetterRetryConfiguration.class)
@AllArgsConstructor
public class RabbitMQConfiguration {

  private final RoutingKeyProperties routingKeyProperties;

  private final QueueProperties queueProperties;

  private final ExchangeProperties exchangeProperties;

  @Bean
  public DirectExchange bookingRoomExchange() {
    return new DirectExchange(this.exchangeProperties.bookingRoom());
  }

  @Bean
  public Queue bookingRoomRequestedQueue() {
    return DeadLetterTopology.queue(this.queueProperties.bookingRoomRequested());
  }

  @Bean
  public Queue bookingRoomConfirmationQueue() {
    return DeadLetterTopology.queue(this.queueProperties.bookingRoomConfirmation());
  }

  @Bean
  public Queue bookingRoomStatusChangedQueue() {
    return DeadLetterTopology.queue(this.queueProperties.bookingRoomStatusChanged());
  }

  /**
   * Este serviço declara também a fila de confirmação, que consome o {@code hotel-service}, e
   * por isso declara a DLQ dela: uma fila com dead-letter configurado e sem destino para ele
   * descarta a mensagem rejeitada em silêncio.
   */
  @Bean
  public Declarables deadLetterDeclarables() {
    return DeadLetterTopology.declarables(
      this.queueProperties.bookingRoomRequested(),
      this.queueProperties.bookingRoomConfirmation(),
      this.queueProperties.bookingRoomStatusChanged()
    );
  }

  @Bean
  public Binding bookingRoomConfirmationBinding(
    final DirectExchange bookingRoomExchange,
    final Queue bookingRoomConfirmationQueue
  ) {
    return BindingBuilder.bind(bookingRoomConfirmationQueue)
      .to(bookingRoomExchange)
      .with(this.routingKeyProperties.bookingRoomConfirmation());
  }

  /**
   * O conversor vem do {@code commons}, que fixa nele os pacotes confiáveis do contrato de
   * eventos — este converter, ao contrário do {@code Jackson2JsonMessageConverter} que ele
   * substitui, nasce confiando apenas em {@code java.util} e {@code java.lang} — e sinaliza
   * como falha de conversão o tipo recusado, para que o retry o mande direto à DLQ.
   */
  @Bean
  public MessageConverter jsonMessageConverter(final JsonMapper jsonMapper) {
    return new EventMessageConverter(jsonMapper);
  }

}
