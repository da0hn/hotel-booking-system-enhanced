package com.hotel.booking.system.customer.service.core.domain.entity;

import com.hotel.booking.system.commons.core.domain.valueobject.*;
import com.hotel.booking.system.customer.service.core.domain.exception.CustomerDomainException;
import com.hotel.booking.system.customer.service.core.domain.exception.CustomerNotFoundException;
import com.hotel.booking.system.customer.service.core.domain.exception.ReservationOrderNotFoundException;
import com.hotel.booking.system.customer.service.core.domain.valueobject.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@DisplayName("Domínio de clientes")
class CustomerDomainTest {
  @Test
  @DisplayName("inicializa a identidade uma vez e não repete a primeira transição")
  void inicializaIdentidadeUmaVezENaoRepeteAPrimeiraTransicao() {
    final var order = ReservationOrder.builder().build();
    order.initialize();
    final var id = order.getId();
    order.initialize();
    order.updateStatus(CustomerReservationStatus.AWAITING_PAYMENT);
    assertThat(order.getId()).isEqualTo(id);
    assertThat(order.getCurrentStatus()).isEqualTo(CustomerReservationStatus.AWAITING_PAYMENT);
    assertThat(order.getTimeline()).hasSize(2);
    assertThat(order.getTimeline()).allSatisfy(item -> {
      assertThat(item.getId()).isNotNull();
      assertThat(item.getOccurredAt()).isBeforeOrEqualTo(Instant.now());
    });
  }

  @Test
  @DisplayName("registra cada transição distinta da saga, na sequência em que chegam")
  void registraCadaTransicaoDistintaEmSequencia() {
    final var order = ReservationOrder.builder().build();
    order.initialize();

    assertThat(order.updateStatus(CustomerReservationStatus.AWAITING_PAYMENT)).isTrue();
    assertThat(order.updateStatus(CustomerReservationStatus.PAYMENT_CONFIRMED)).isTrue();
    assertThat(order.updateStatus(CustomerReservationStatus.RESERVED)).isTrue();

    assertThat(order.getCurrentStatus()).isEqualTo(CustomerReservationStatus.RESERVED);
    assertThat(order.getTimeline()).extracting(ReservationOrderTimeline::getStatus).containsExactly(
      CustomerReservationStatus.AWAITING_RESERVATION,
      CustomerReservationStatus.AWAITING_PAYMENT,
      CustomerReservationStatus.PAYMENT_CONFIRMED,
      CustomerReservationStatus.RESERVED
    );
  }

  @Test
  @DisplayName("ignora o status que já é o da última entrada da timeline")
  void ignoraStatusIgualAoDaUltimaEntrada() {
    final var order = ReservationOrder.builder().build();
    order.initialize();
    order.updateStatus(CustomerReservationStatus.AWAITING_PAYMENT);

    final var applied = order.updateStatus(CustomerReservationStatus.AWAITING_PAYMENT);

    assertThat(applied).isFalse();
    assertThat(order.getTimeline()).hasSize(2);
    assertThat(order.getCurrentStatus()).isEqualTo(CustomerReservationStatus.AWAITING_PAYMENT);
  }

  @Test
  @DisplayName("ignora a falha repetida com o mesmo status e os mesmos motivos")
  void ignoraFalhaRepetidaComOMesmoStatusEOsMesmosMotivos() {
    final var order = ReservationOrder.builder().build();
    order.initialize();

    final var first = order.updateToFailureStatus(CustomerReservationStatus.PAYMENT_FAILED,
      FailureMessages.newInstance(List.of("Cartão recusado")));
    final var repeated = order.updateToFailureStatus(CustomerReservationStatus.PAYMENT_FAILED,
      FailureMessages.newInstance(List.of("Cartão recusado")));

    assertThat(first).isTrue();
    assertThat(repeated).isFalse();
    assertThat(order.getTimeline()).hasSize(2);
  }

  @Test
  @DisplayName("grava a nova falha quando o mesmo status volta com outro motivo")
  void gravaANovaFalhaQuandoOMesmoStatusVoltaComOutroMotivo() {
    final var order = ReservationOrder.builder().build();
    order.initialize();
    order.updateToFailureStatus(CustomerReservationStatus.PAYMENT_FAILED,
      FailureMessages.newInstance(List.of("Cartão recusado")));

    final var applied = order.updateToFailureStatus(CustomerReservationStatus.PAYMENT_FAILED,
      FailureMessages.newInstance(List.of("Saldo insuficiente")));

    assertThat(applied).isTrue();
    assertThat(order.getTimeline()).extracting(ReservationOrderTimeline::getReason)
      .containsExactly(null, "Cartão recusado", "Saldo insuficiente");
  }

  @Test
  @DisplayName("compara com a entrada mais recente pelo instante, e não pela posição na lista")
  void comparaComAEntradaMaisRecentePeloInstante() {
    final var old = new ReservationOrderTimeline(ReservationOrderTimelineId.newInstance(),
      CustomerReservationStatus.AWAITING_RESERVATION, null, Instant.parse("2026-01-01T00:00:00Z"));
    final var recent = new ReservationOrderTimeline(ReservationOrderTimelineId.newInstance(),
      CustomerReservationStatus.AWAITING_PAYMENT, null, Instant.parse("2026-01-02T00:00:00Z"));
    final var order = ReservationOrder.builder()
      .timeline(Timeline.of(List.of(recent, old)))
      .currentStatus(CustomerReservationStatus.AWAITING_PAYMENT)
      .build();

    assertThat(order.updateStatus(CustomerReservationStatus.AWAITING_PAYMENT)).isFalse();
    assertThat(order.updateStatus(CustomerReservationStatus.PAYMENT_CONFIRMED)).isTrue();
  }

  @Test
  @DisplayName("trata motivo nulo e em branco como a mesma ausência de motivo")
  void trataMotivoNuloEEmBrancoComoAMesmaAusencia() {
    final var semMotivo = new ReservationOrderTimeline(ReservationOrderTimelineId.newInstance(),
      CustomerReservationStatus.AWAITING_PAYMENT, "", Instant.parse("2026-01-01T00:00:00Z"));
    final var order = ReservationOrder.builder()
      .timeline(Timeline.of(List.of(semMotivo)))
      .currentStatus(CustomerReservationStatus.AWAITING_PAYMENT)
      .build();

    assertThat(order.updateStatus(CustomerReservationStatus.AWAITING_PAYMENT)).isFalse();
  }

  @Test
  @DisplayName("guarda motivos de falha e rejeita status de sucesso")
  void falhaGuardaTodasAsRazoesERejeitaStatusDeSucessoSemMutacao() {
    final var order = ReservationOrder.builder().build();
    order.initialize();
    final var messages = FailureMessages.newInstance(List.of("Cartão recusado", "Saldo insuficiente"));
    assertThatThrownBy(() -> order.updateToFailureStatus(CustomerReservationStatus.RESERVED, messages))
      .isInstanceOf(CustomerDomainException.class);
    assertThat(order.getCurrentStatus()).isEqualTo(CustomerReservationStatus.AWAITING_RESERVATION);
    assertThat(order.getTimeline()).hasSize(1);
    order.updateToFailureStatus(CustomerReservationStatus.PAYMENT_FAILED, messages);
    assertThat(order.getCurrentStatus()).isEqualTo(CustomerReservationStatus.PAYMENT_FAILED);
    assertThat(order.getTimeline()).extracting(ReservationOrderTimeline::getReason)
      .contains("Cartão recusado\nSaldo insuficiente");
  }

  @Test
  @DisplayName("ordena a linha do tempo sem alterar a ordem armazenada")
  void timelineOrdenaMaisRecentePrimeiroSemAlterarOrdemArmazenada() {
    final var old = new ReservationOrderTimeline(ReservationOrderTimelineId.newInstance(),
      CustomerReservationStatus.AWAITING_RESERVATION, null, Instant.parse("2026-01-01T00:00:00Z"));
    final var recent = new ReservationOrderTimeline(ReservationOrderTimelineId.newInstance(),
      CustomerReservationStatus.RESERVED, null, Instant.parse("2026-01-02T00:00:00Z"));
    final var timeline = Timeline.of(List.of(old, recent));
    assertThat(timeline.mapToListOf(ReservationOrderTimeline::getStatus))
      .containsExactly(CustomerReservationStatus.RESERVED, CustomerReservationStatus.AWAITING_RESERVATION);
    assertThat(timeline).containsExactly(old, recent);
  }

  @Test
  @DisplayName("valida e formata CPF sem perder zeros")
  void cpfValidaComprimentoEFormataSemPerderZeros() {
    assertThatThrownBy(() -> new Cpf(null)).isInstanceOf(CustomerDomainException.class);
    assertThatThrownBy(() -> new Cpf("123")).isInstanceOf(CustomerDomainException.class);
    final var customer = new Customer(CustomerId.newInstance(), "Ana", new Cpf("01234567890"));
    assertThat(customer.getName()).isEqualTo("Ana");
    assertThat(customer.getCpf().formatted()).isEqualTo("012.345.678-90");
    assertThat(customer.getCpf().value()).isEqualTo("01234567890");
  }

  @Test
  @DisplayName("preserva o UUID e rejeita entrada inválida no ID da timeline")
  void idDaTimelinePreservaUuidERejeitaEntradaInvalida() {
    final var uuid = UUID.randomUUID();
    assertThat(ReservationOrderTimelineId.of(uuid).toString()).isEqualTo(uuid.toString());
    assertThat(ReservationOrderTimelineId.of(uuid.toString())).isEqualTo(ReservationOrderTimelineId.of(uuid));
    assertThatThrownBy(() -> ReservationOrderTimelineId.of((UUID) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ReservationOrderTimelineId.of((String) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ReservationOrderTimelineId.of("invalid")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("expõe os erros de domínio e a saída vazia da inicialização")
  void exposesDomainErrorsAndEmptyInitializationOutput() {
    final var cause = new IllegalStateException("cause");
    assertThat(new CustomerDomainException("message")).hasMessage("message");
    assertThat(new CustomerDomainException("message", cause)).hasCause(cause);
    assertThat(new CustomerNotFoundException()).hasMessage("customer.not.found");
    assertThat(new CustomerNotFoundException("message", cause)).hasCause(cause);
    assertThat(new ReservationOrderNotFoundException()).hasMessage("customer.reservation-order.not.found");
    assertThat(new ReservationOrderNotFoundException("message", cause)).hasCause(cause);
    assertThat(new com.hotel.booking.system.customer.service.core.application.dto.InitializeReservationOrderOutput()).isNotNull();
  }

  @Test
  @DisplayName("reconstitui uma reserva persistida mantendo sua identidade")
  void reconstitutesPersistedReservationOrderWithItsIdentity() {
    final var id = ReservationOrderId.newInstance();
    assertThat(new ReservationOrder(id).getId()).isEqualTo(id);
  }
}
