package com.hotel.booking.system.customer.service.data.db;

import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import com.hotel.booking.system.customer.service.data.db.entity.ReservationOrderEntity;
import com.hotel.booking.system.customer.service.data.db.entity.ReservationOrderHistoryEntity;
import com.hotel.booking.system.customer.service.data.db.repository.CustomerJpaRepository;
import com.hotel.booking.system.customer.service.data.db.repository.ReservationOrderJpaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cobre a gravação e a leitura da timeline do cliente — a projeção que o {@code GET
 * /customers/&#123;id&#125;/reservation-order/&#123;id&#125;} devolve.
 *
 * <p>O que atravessa o mapeamento aqui e não aparece em nenhuma consulta escrita à mão é o
 * {@link Instant} do {@code occurred_at}: a saga grava cada transição com a hora do momento,
 * e o cliente lê essa hora de volta. O tipo por baixo muda na Fase 2, e a ordem da timeline
 * depende dele.</p>
 */
@DisplayName("Timeline da reserva do cliente")
class ReservationOrderJpaRepositoryIT extends AbstractDatabaseIT {

  private static final UUID CLIENTE_GABRIEL = UUID.fromString("f1e28a47-8852-45e8-b8e1-e6701633dd56");

  @Autowired
  private ReservationOrderJpaRepository repository;

  @Autowired
  private CustomerJpaRepository customerRepository;

  @Autowired
  private EntityManager entityManager;

  @Test
  @DisplayName("carrega os clientes do seed com o acento preservado")
  void carregaClientesDoSeed() {
    final var clientes = this.customerRepository.findAll();

    assertThat(clientes)
      .hasSize(4)
      .extracting("name")
      .contains("Gabriel Honda", "Icaro Martins");
  }

  @Test
  @DisplayName("grava o pedido junto com toda a timeline em cascata")
  void gravaPedidoComTimelineEmCascata() {
    final var pedido = this.repository.saveAndFlush(this.novoPedido(
      this.historico(CustomerReservationStatus.AWAITING_RESERVATION, Instant.parse("2026-06-01T12:00:00Z")),
      this.historico(CustomerReservationStatus.AWAITING_PAYMENT, Instant.parse("2026-06-01T12:00:05Z"))
    ));
    this.entityManager.clear();

    final var lido = this.repository.findById(pedido.getId()).orElseThrow();

    assertThat(lido.getHistory())
      .as("o cascade ALL do `history` precisa continuar gravando as duas linhas numa chamada só")
      .hasSize(2)
      .extracting(ReservationOrderHistoryEntity::getStatus)
      .containsExactlyInAnyOrder(CustomerReservationStatus.AWAITING_RESERVATION, CustomerReservationStatus.AWAITING_PAYMENT);
  }

  /**
   * A coluna {@code occurred_at} é {@code datetime} sem precisão fracionária declarada, o que
   * no MySQL significa <strong>zero</strong> casas: o instante é truncado para o segundo na
   * gravação. O teste usa um valor já truncado de propósito — não é por acaso, é para separar
   * o que se quer provar (o instante volta igual) do que hoje é uma perda silenciosa de
   * precisão que a Fase 2 pode muito bem eliminar.
   */
  @Test
  @DisplayName("devolve o instante de cada transição sem deslocamento")
  void devolveInstanteDeCadaTransicaoSemDeslocamento() {
    final var momento = Instant.parse("2026-06-10T21:45:30Z").truncatedTo(ChronoUnit.SECONDS);
    final var pedido = this.repository.saveAndFlush(this.novoPedido(
      this.historico(CustomerReservationStatus.AWAITING_RESERVATION, momento)
    ));
    this.entityManager.clear();

    final var lido = this.repository.findById(pedido.getId()).orElseThrow();

    assertThat(lido.getHistory())
      .singleElement()
      .extracting(ReservationOrderHistoryEntity::getOccurredAt)
      .isEqualTo(momento);
  }

  @Test
  @DisplayName("guarda o status atual como texto do enum")
  void guardaStatusAtualComoTextoDoEnum() {
    final var pedido = this.repository.saveAndFlush(this.novoPedido());
    this.entityManager.clear();

    final var gravado = this.entityManager
      .createNativeQuery("select current_status from reservation_order where id = :id")
      .setParameter("id", pedido.getId().toString())
      .getSingleResult();

    assertThat(gravado)
      .as("o `@Enumerated(STRING)` grava o nome; uma coluna encolhida na migração truncaria o valor")
      .isEqualTo(CustomerReservationStatus.AWAITING_RESERVATION.name());
  }

  private ReservationOrderEntity novoPedido(final ReservationOrderHistoryEntity... historico) {
    final var pedido = ReservationOrderEntity.builder()
      .id(UUID.randomUUID())
      .hotelId(UUID.randomUUID())
      .customer(this.customerRepository.findById(CLIENTE_GABRIEL).orElseThrow())
      .guests(2)
      .checkIn(LocalDate.of(2026, 6, 20))
      .checkOut(LocalDate.of(2026, 6, 25))
      .totalPrice(new BigDecimal("800"))
      .currentStatus(CustomerReservationStatus.AWAITING_RESERVATION)
      .history(new LinkedHashSet<>(Set.of(historico)))
      .build();

    pedido.getHistory().forEach(transicao -> transicao.setReservationOrder(pedido));

    return pedido;
  }

  private ReservationOrderHistoryEntity historico(final CustomerReservationStatus status, final Instant momento) {
    return ReservationOrderHistoryEntity.builder()
      .id(UUID.randomUUID())
      .status(status)
      .occurredAt(momento)
      .build();
  }

}
