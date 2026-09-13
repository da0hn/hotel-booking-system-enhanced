package com.hotel.booking.system.payment.service.core.application;

import com.hotel.booking.system.commons.core.domain.event.*;
import com.hotel.booking.system.commons.core.domain.valueobject.*;
import com.hotel.booking.system.payment.service.core.application.dto.*;
import com.hotel.booking.system.payment.service.core.application.mapper.PaymentUseCaseMapperImpl;
import com.hotel.booking.system.payment.service.core.application.messaging.PaymentRequestedHandlerImpl;
import com.hotel.booking.system.payment.service.core.application.usecase.PayOrderUseCaseMockImpl;
import com.hotel.booking.system.payment.service.core.ports.api.usecase.PayOrderUseCase;
import com.hotel.booking.system.payment.service.core.ports.spi.messaging.publisher.PaymentResponsePublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Fluxo de pagamento")
class PaymentFlowTest {

  private final PaymentUseCaseMapperImpl mapper = new PaymentUseCaseMapperImpl();

  /**
   * Congela o sorteio no limite: hoje empate com o percentual configurado falha.
   * Não depende de repetição probabilística para exercitar aprovação e recusa.
   */
  @ParameterizedTest
  @DisplayName("aprova ou recusa conforme o percentual e o limite do sorteio")
  @CsvSource({"0, 0, COMPLETED", "100, 0, FAILED", "120, 0, FAILED", "50, 50, FAILED", "50, 51, COMPLETED"})
  void pagaOuRecusaConformePercentualELimiteDoSorteio(final int percentage, final int draw, final PaymentStatus expected) {
    final var input = this.input();
    final var before = LocalDateTime.now();
    try (final var random = mockConstruction(Random.class, (mock, context) -> when(mock.nextInt(0, 100)).thenReturn(draw))) {
      final var output = new PayOrderUseCaseMockImpl(this.mapper, percentage).execute(input);
      assertThat(output.status()).isEqualTo(expected);
      assertThat(output.payment().getId().getValue()).isNotNull();
      assertThat(output.payment().getCreatedAt()).isBetween(before, LocalDateTime.now());
      assertThat(output.payment().getTotalPrice().getValue()).isEqualByComparingTo("1234.5678");
      assertThat(output.payment().getCustomerId().toString()).isEqualTo(input.customerId());
      assertThat(output.payment().getReservationOrderId().toString()).isEqualTo(input.reservationOrderId());
      assertThat(output.payment().getStatus()).as("o resultado carrega o status; a entidade atual não o recebe").isNull();
      if (expected == PaymentStatus.FAILED) {
        assertThat(output.failureMessages()).containsExactly("Customer doesn't have enough credit for payment");
      } else {
        assertThat(output.failureMessages()).isEmpty();
      }
      assertThat(random.constructed()).hasSize(percentage == 0 || percentage >= 100 ? 0 : 1);
    }
  }

  @ParameterizedTest
  @DisplayName("preserva correlação, precisão e motivos ao publicar a resposta")
  @CsvSource({"true, FAILED", "false, COMPLETED"})
  void handlerPreservaCorrelacaoPrecisaoEMotivos(final boolean failed, final PaymentStatus status) {
    final var input = this.input();
    final var event = PaymentRequestedEvent.builder().bookingRoomId(input.bookingRoomId())
      .reservationOrderId(input.reservationOrderId()).customerId(input.customerId()).totalPrice(input.totalPrice()).build();
    final var useCase = mock(PayOrderUseCase.class);
    final var publisher = mock(PaymentResponsePublisher.class);
    final var failures = failed ? FailureMessages.of("saldo insuficiente") : FailureMessages.empty();
    final var payment = this.mapper.payOrderInputToPayment(input);
    when(useCase.execute(input)).thenReturn(new PayOrderOutput(payment, status, failures));
    new PaymentRequestedHandlerImpl(useCase, this.mapper, publisher).handle(event);
    final var captured = ArgumentCaptor.forClass(PaymentResponseEvent.class);
    verify(useCase).execute(input);
    verify(publisher).publish(captured.capture());
    if (failed) {
      final var response = (PaymentFailedEvent) captured.getValue();
      assertThat(response.getReservationOrderId()).isEqualTo(input.reservationOrderId());
      assertThat(response.getCustomerId()).isEqualTo(input.customerId());
      assertThat(response.getTotalPrice()).isEqualByComparingTo(input.totalPrice());
      assertThat(response.getStatus()).isEqualTo(status);
      assertThat(response.getFailureMessages()).containsExactly("saldo insuficiente");
    } else {
      final var response = (PaymentCompletedEvent) captured.getValue();
      assertThat(response.getReservationOrderId()).isEqualTo(input.reservationOrderId());
      assertThat(response.getCustomerId()).isEqualTo(input.customerId());
      assertThat(response.getTotalPrice()).isEqualByComparingTo(input.totalPrice());
      assertThat(response.getStatus()).isEqualTo(status);
    }
    verifyNoMoreInteractions(publisher);
  }

  @Test
  @DisplayName("não publica resposta quando o caso de uso falha")
  void falhaNoCasoDeUsoImpedePublicacao() {
    final var useCase = mock(PayOrderUseCase.class);
    final var publisher = mock(PaymentResponsePublisher.class);
    final var failure = new IllegalStateException("indisponível");
    when(useCase.execute(any())).thenThrow(failure);
    assertThatThrownBy(() -> new PaymentRequestedHandlerImpl(useCase, this.mapper, publisher)
      .handle(PaymentRequestedEvent.builder().build())).isSameAs(failure);
    verifyNoInteractions(publisher);
  }

  @Test
  @DisplayName("propaga a interrupção durante a simulação de pagamento")
  void propagatesInterruptionDuringPaymentSimulation() {
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> new PayOrderUseCaseMockImpl(this.mapper, 0).execute(this.input()))
        .isInstanceOf(InterruptedException.class);
    } finally {
      Thread.interrupted();
    }
  }

  private PayOrderInput input() {
    return new PayOrderInput(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
      UUID.randomUUID().toString(), new BigDecimal("1234.5678"));
  }
}
