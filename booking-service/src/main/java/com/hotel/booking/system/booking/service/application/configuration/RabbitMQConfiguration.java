package com.hotel.booking.system.booking.service.application.configuration;

import com.hotel.booking.system.commons.core.domain.event.TrustedEventPackages;
import com.hotel.booking.system.booking.service.application.configuration.properties.ExchangeProperties;
import com.hotel.booking.system.booking.service.application.configuration.properties.QueueProperties;
import com.hotel.booking.system.booking.service.application.configuration.properties.RoutingKeyProperties;
import lombok.AllArgsConstructor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
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
    return new Queue(this.queueProperties.bookingRoomRequested(), true);
  }

  @Bean
  public Queue bookingRoomConfirmationQueue() {
    return new Queue(this.queueProperties.bookingRoomConfirmation(), true);
  }

  @Bean
  public Queue bookingRoomStatusChangedQueue() {
    return new Queue(this.queueProperties.bookingRoomStatusChanged(), true);
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

  @Bean
  public MessageConverter jsonMessageConverter(final JsonMapper jsonMapper) {
    // Os pacotes confiáveis são declarados porque este converter, ao contrário
    // do `Jackson2JsonMessageConverter` que ele substitui, nasce confiando só em
    // `java.util` e `java.lang`. A lista vive no `commons`, junto do contrato que
    // ela protege.
    return new JacksonJsonMessageConverter(jsonMapper, TrustedEventPackages.names());
  }

}
