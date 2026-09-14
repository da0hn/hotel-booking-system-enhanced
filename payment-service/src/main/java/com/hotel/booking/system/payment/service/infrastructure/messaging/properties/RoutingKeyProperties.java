package com.hotel.booking.system.payment.service.infrastructure.messaging.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rabbitmq.routing-key")
public record RoutingKeyProperties(
  String paymentRequest,
  String paymentConfirmation
) {

}
