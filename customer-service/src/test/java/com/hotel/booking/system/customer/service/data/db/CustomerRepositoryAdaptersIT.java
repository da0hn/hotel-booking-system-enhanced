package com.hotel.booking.system.customer.service.data.db;

import com.hotel.booking.system.commons.core.domain.valueobject.*;
import com.hotel.booking.system.customer.service.core.domain.entity.*;
import com.hotel.booking.system.customer.service.core.domain.exception.*;
import com.hotel.booking.system.customer.service.data.db.entity.ReservationOrderEntity;
import com.hotel.booking.system.customer.service.data.db.mapper.impl.CustomerDatabaseMapperImpl;
import com.hotel.booking.system.customer.service.data.db.repository.adapters.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Import({CustomerDatabaseMapperImpl.class, CustomerRepositoryAdapter.class, ReservationOrderRepositoryAdapter.class})
@DisplayName("Adaptadores de repositório de clientes")
class CustomerRepositoryAdaptersIT extends AbstractDatabaseIT {
  @Autowired private CustomerRepositoryAdapter customers;
  @Autowired private ReservationOrderRepositoryAdapter orders;
  @Autowired private EntityManager entityManager;
  private static final CustomerId CLIENTE = CustomerId.of("f1e28a47-8852-45e8-b8e1-e6701633dd56");

  @Test
  @DisplayName("consulta cliente do seed e traduz ausências")
  void adapterConsultaClienteDoSeedETraduzAusencia() {
    assertThat(this.customers.customerExistsBy(CLIENTE)).isTrue();
    assertThat(this.customers.findById(CLIENTE).getName()).isEqualTo("Gabriel Honda");
    assertThat(this.customers.findCustomerEntityById(CLIENTE).getId()).isEqualTo(CLIENTE.getValue());
    final var missing = CustomerId.newInstance();
    assertThat(this.customers.customerExistsBy(missing)).isFalse();
    assertThatThrownBy(() -> this.customers.findById(missing)).isInstanceOf(CustomerNotFoundException.class);
    assertThatThrownBy(() -> this.customers.findCustomerEntityById(missing)).isInstanceOf(CustomerNotFoundException.class);
    assertThatThrownBy(() -> this.orders.findById(ReservationOrderId.newInstance()))
      .isInstanceOf(ReservationOrderNotFoundException.class);
  }

  @Test
  @DisplayName("persiste a projeção completa e adiciona falha ao histórico")
  void adapterPersisteProjecaoCompletaEAcrescentaFalhaSemPerderHistorico() {
    final var order = ReservationOrder.builder().id(ReservationOrderId.newInstance()).customerId(CLIENTE)
      .hotelId(HotelId.newInstance()).guests(3).checkIn(LocalDate.of(2027, 1, 1))
      .checkOut(LocalDate.of(2027, 1, 3)).totalPrice(Money.of(new BigDecimal("1234.5678"))).build();
    order.initialize();
    this.orders.save(order);
    this.entityManager.flush();
    this.entityManager.clear();
    final var restored = this.orders.findById(order.getId());
    assertThat(restored.getCustomerId()).isEqualTo(CLIENTE);
    assertThat(restored.getHotelId()).isEqualTo(order.getHotelId());
    assertThat(restored.getGuests()).isEqualTo(3);
    assertThat(restored.getCheckIn()).isEqualTo(order.getCheckIn());
    assertThat(restored.getCheckOut()).isEqualTo(order.getCheckOut());
    assertThat(restored.getTotalPrice().getValue()).isEqualByComparingTo("1234.5678");
    assertThat(restored.getTimeline()).hasSize(1);
    restored.updateToFailureStatus(CustomerReservationStatus.RESERVATION_FAILED,
      FailureMessages.newInstance(List.of("Quarto indisponível", "Reserva concorrente")));
    this.orders.save(restored);
    this.entityManager.flush();
    this.entityManager.clear();
    final var failed = this.orders.findById(order.getId());
    assertThat(failed.getCurrentStatus()).isEqualTo(CustomerReservationStatus.RESERVATION_FAILED);
    assertThat(failed.getTimeline()).hasSize(2).extracting(ReservationOrderTimeline::getReason)
      .contains("Quarto indisponível\nReserva concorrente");
  }

  @Test
  @DisplayName("deduplica entidades de persistência pelo identificador do pedido")
  void identidadeDaEntidadePermiteDeduplicacaoPorPedido() {
    final var uuid = UUID.randomUUID();
    final var first = ReservationOrderEntity.builder().id(uuid).build();
    final var same = ReservationOrderEntity.builder().id(uuid).build();
    assertThat(first.equals(first)).isTrue();
    assertThat(first.equals(null)).isFalse();
    assertThat(first.equals("other type")).isFalse();
    assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
    assertThat(first).isNotEqualTo(ReservationOrderEntity.builder().id(UUID.randomUUID()).build());
  }
}
