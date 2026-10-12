package com.hotel.booking.system.customer.service.infrastructure.configuration;

import com.hotel.booking.system.commons.infrastructure.messaging.DeadLetterRetryConfiguration;
import com.hotel.booking.system.commons.infrastructure.messaging.DeadLetterTopology;
import com.hotel.booking.system.commons.infrastructure.messaging.EventMessageConverter;
import com.hotel.booking.system.customer.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.customer.service.infrastructure.messaging.properties.QueueProperties;
import com.hotel.booking.system.customer.service.infrastructure.messaging.properties.RoutingKeyProperties;
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
  public DirectExchange customerBookingExchange() {
    return new DirectExchange(this.exchangeProperties.customerBooking());
  }

  @Bean
  public Queue customerBookingUpdateQueue() {
    return DeadLetterTopology.queue(this.queueProperties.customerBookingUpdate());
  }

  @Bean
  public Declarables deadLetterDeclarables() {
    return DeadLetterTopology.declarables(this.queueProperties.customerBookingUpdate());
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
