package com.hotel.booking.system.customer.service.application.configuration;

import com.hotel.booking.system.commons.core.domain.event.TrustedEventPackages;
import com.hotel.booking.system.customer.service.application.configuration.properties.ExchangeProperties;
import com.hotel.booking.system.customer.service.application.configuration.properties.QueueProperties;
import com.hotel.booking.system.customer.service.application.configuration.properties.RoutingKeyProperties;
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
  public DirectExchange customerBookingExchange() {
    return new DirectExchange(this.exchangeProperties.customerBooking());
  }

  @Bean
  public Queue customerBookingUpdateQueue() {
    return new Queue(this.queueProperties.customerBookingUpdate(), true);
  }

  @Bean
  public Binding customerBookingUpdateBinding(
    final DirectExchange customerBookingExchange,
    final Queue customerBookingUpdateQueue
  ) {
    return BindingBuilder.bind(customerBookingUpdateQueue)
      .to(customerBookingExchange)
      .with(this.routingKeyProperties.customerBookingUpdate());
  }

  /**
   * Os pacotes confiáveis são declarados porque este converter, ao contrário do
   * {@code Jackson2JsonMessageConverter} que ele substitui, nasce confiando apenas em
   * {@code java.util} e {@code java.lang}. A lista vive no {@code commons}, junto do
   * contrato de eventos que ela protege.
   */
  @Bean
  public MessageConverter jsonMessageConverter(final JsonMapper jsonMapper) {
    return new JacksonJsonMessageConverter(jsonMapper, TrustedEventPackages.names());
  }

}
