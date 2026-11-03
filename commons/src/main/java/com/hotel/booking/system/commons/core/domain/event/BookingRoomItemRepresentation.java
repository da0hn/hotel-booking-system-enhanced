package com.hotel.booking.system.commons.core.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.math.BigDecimal;

/**
 * Item de quarto dentro de {@code rooms}. É um payload aninhado, não um evento: não tem
 * {@code eventId}, porque o identificador pertence à mensagem que o carrega.
 */
@Builder
@Getter
@AllArgsConstructor
public final class BookingRoomItemRepresentation {

  private final String roomId;
  private final BigDecimal price;
  private final Integer quantity;

  @Override
  public String toString() {
    return ToStringBuilder.reflectionToString(this, ToStringStyle.JSON_STYLE);
  }
}
