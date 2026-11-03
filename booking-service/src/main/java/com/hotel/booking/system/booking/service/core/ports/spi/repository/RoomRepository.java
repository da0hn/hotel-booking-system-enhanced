package com.hotel.booking.system.booking.service.core.ports.spi.repository;

import com.hotel.booking.system.booking.service.core.domain.entity.Room;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;

import java.util.Collection;
import java.util.List;

public interface RoomRepository {

  /**
   * Carrega os quartos e os trava para escrita até o fim da transação corrente.
   *
   * <p>É o que impede duas reservas concorrentes de lerem a mesma ocupação e gravarem as duas:
   * quem chega depois espera aqui, e ao acordar enxerga o que a primeira comitou. Por isso a
   * chamada vem <em>antes</em> de qualquer leitura de ocupação, e exige que o chamador já esteja
   * numa transação, que é quem segura o lock.</p>
   *
   * <p>Os quartos voltam numa ordem estável, a mesma para qualquer chamador, para que pedidos
   * com os mesmos quartos em ordens diferentes não travem um ao outro.</p>
   */
  List<Room> lockAllByRoomId(Collection<? extends RoomId> ids);

}
