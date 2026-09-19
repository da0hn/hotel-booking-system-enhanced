package com.hotel.booking.system.booking.service.infrastructure.messaging.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rabbitmq.queue")
public record QueueProperties(
  String bookingRoomRequested,
  String bookingRoomConfirmation,
  String bookingRoomStatusChanged
) {

}
