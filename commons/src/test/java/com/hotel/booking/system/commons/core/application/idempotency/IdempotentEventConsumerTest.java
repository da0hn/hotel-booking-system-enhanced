package com.hotel.booking.system.commons.core.application.idempotency;

import com.hotel.booking.system.commons.core.domain.event.BookingRoomRequestedEvent;
import com.hotel.booking.system.commons.core.domain.event.PaymentCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Consumo idempotente de eventos")
class IdempotentEventConsumerTest {

  private ProcessedEventRepository repository;

  private IdempotentEventConsumer consumer;

  private final AtomicInteger effects = new AtomicInteger();

  @BeforeEach
  void setUp() {
    this.repository = mock(ProcessedEventRepository.class);
    this.consumer = new IdempotentEventConsumer(this.repository);
    this.effects.set(0);
  }

  @Test
  @DisplayName("processa o evento novo e o registra com o tipo completo da classe")
  void processaEventoNovoERegistraComOTipo() {
    final var event = BookingRoomRequestedEvent.builder().reservationOrderId("order").build();
    when(this.repository.register(event.getEventId(), BookingRoomRequestedEvent.class.getName())).thenReturn(true);

    final var processed = this.consumer.consume(event, this.effects::incrementAndGet);

    assertThat(processed).isTrue();
    assertThat(this.effects).hasValue(1);
    verify(this.repository).register(event.getEventId(), BookingRoomRequestedEvent.class.getName());
  }

  @Test
  @DisplayName("ignora a reentrega do mesmo eventId sem repetir o efeito")
  void ignoraReentregaSemRepetirOEfeito() {
    final var event = PaymentCompletedEvent.builder().reservationOrderId("order").build();
    when(this.repository.register(event.getEventId(), PaymentCompletedEvent.class.getName()))
      .thenReturn(true)
      .thenReturn(false);

    final var first = this.consumer.consume(event, this.effects::incrementAndGet);
    final var redelivery = this.consumer.consume(event, this.effects::incrementAndGet);

    assertThat(first).isTrue();
    assertThat(redelivery).isFalse();
    assertThat(this.effects).hasValue(1);
  }

  /**
   * O registro vem antes do efeito de propósito: é a chave primária do banco que serializa duas
   * entregas simultâneas. Registrar depois deixaria as duas executarem o efeito.
   */
  @Test
  @DisplayName("registra antes de processar")
  void registraAntesDeProcessar() {
    final var event = BookingRoomRequestedEvent.builder().build();
    when(this.repository.register(anyString(), anyString())).thenAnswer(invocation -> {
      assertThat(this.effects).as("o efeito ainda não foi aplicado").hasValue(0);
      return true;
    });

    this.consumer.consume(event, this.effects::incrementAndGet);

    assertThat(this.effects).hasValue(1);
  }

  /**
   * Num repositório transacional o rollback desfaz o registro; num que não é (memória), quem
   * desfaz é o {@code release}. Sem ele a nova tentativa do retry encontraria o evento
   * "processado" e o descartaria com ack: a mensagem seria perdida sem nenhum efeito.
   */
  @Test
  @DisplayName("libera o registro e propaga a exceção quando o processamento falha")
  void liberaORegistroQuandoOProcessamentoFalha() {
    final var event = BookingRoomRequestedEvent.builder().build();
    when(this.repository.register(anyString(), anyString())).thenReturn(true);
    final var failure = new IllegalStateException("banco indisponível");

    assertThatThrownBy(() -> this.consumer.consume(event, () -> {
      throw failure;
    })).isSameAs(failure);

    verify(this.repository).release(event.getEventId());
  }

  @Test
  @DisplayName("não libera nada quando o evento foi ignorado como repetido")
  void naoLiberaQuandoOEventoEhRepetido() {
    final var event = BookingRoomRequestedEvent.builder().build();
    when(this.repository.register(anyString(), anyString())).thenReturn(false);

    this.consumer.consume(event, this.effects::incrementAndGet);

    verify(this.repository, never()).release(any());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  @DisplayName("processa sem deduplicar o evento com eventId nulo ou em branco")
  void processaSemDeduplicarEventoComEventIdNuloOuEmBranco(final String eventId) {
    final var semIdentificador = BookingRoomRequestedEvent.builder().eventId(eventId).build();

    final var processed = this.consumer.consume(semIdentificador, this.effects::incrementAndGet);

    assertThat(processed).isTrue();
    assertThat(this.effects).hasValue(1);
    verifyNoInteractions(this.repository);
  }

}
