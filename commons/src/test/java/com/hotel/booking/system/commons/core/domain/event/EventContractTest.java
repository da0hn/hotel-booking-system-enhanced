package com.hotel.booking.system.commons.core.domain.event;

import com.hotel.booking.system.commons.core.domain.event.customer.CustomerBookingCompletedEvent;
import com.hotel.booking.system.commons.core.domain.event.customer.CustomerBookingInitiatedEvent;
import com.hotel.booking.system.commons.core.domain.event.customer.CustomerBookingPaymentCompletedEvent;
import com.hotel.booking.system.commons.core.domain.event.customer.CustomerBookingPaymentFailedEvent;
import com.hotel.booking.system.commons.core.domain.event.customer.CustomerBookingPaymentRequestedEvent;
import com.hotel.booking.system.commons.core.domain.event.customer.CustomerBookingRejectedEvent;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Trava a propriedade da qual a saga inteira depende: toda classe de evento precisa ser
 * construível pelo Jackson a partir de um objeto JSON.
 * <p>
 * Este teste existe porque a atualização para o Jackson 3 quebrou exatamente isso, e nada
 * acusou: os serviços subiam saudáveis, o {@code POST /hotel/booking} respondia 200, e a
 * saga parava na primeira resposta. Um teste de domínio não alcança esse tipo de falha,
 * porque ela não está no domínio — está na ponte entre ele e a fila.
 */
@DisplayName("Contrato de desserialização dos eventos")
class EventContractTest {

  /**
   * Deliberadamente sem configuração: se um evento só desserializa com um mapper
   * ajustado, ele depende de uma combinação que os quatro serviços precisariam repetir —
   * e que um deles vai esquecer.
   */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /**
   * O header de tipo e o JSON dos logs fazem parte da compatibilidade entre versões.
   * A ida e volta pelo conversor real protege também o pacote confiável do evento.
   */
  @TestFactory
  @DisplayName("preserva o tipo e os campos do evento na conversão AMQP")
  Stream<DynamicTest> contratoAmqpERepresentacaoDoLog() throws IOException {
    final var converter = new JacksonJsonMessageConverter(MAPPER, TrustedEventPackages.names());
    return this.classesDeEvento().stream().map(type -> DynamicTest.dynamicTest(type.getSimpleName(), () -> {
      final var original = MAPPER.readValue("{}", type);
      final var message = converter.toMessage(original, new MessageProperties());
      assertThat(message.getMessageProperties().getHeader("__TypeId__").toString()).isEqualTo(type.getName());
      final var restored = converter.fromMessage(message);
      assertThat(restored).isInstanceOf(type);
      final var originalJson = (ObjectNode) MAPPER.readTree(MAPPER.writeValueAsString(original));
      final var restoredJson = (ObjectNode) MAPPER.readTree(MAPPER.writeValueAsString(restored));
      originalJson.remove("createdAt");
      restoredJson.remove("createdAt");
      assertThat(MAPPER.writeValueAsString(restoredJson)).isEqualTo(MAPPER.writeValueAsString(originalJson));
      assertThat(Boolean.valueOf(MAPPER.readTree(original.toString()).isObject())).isTrue();
    }));
  }

  @Test
  @DisplayName("não expõe o array de pacotes confiáveis para alteração")
  void pacotesConfiaveisNaoPodemSerAlteradosPeloChamador() {
    final var names = TrustedEventPackages.names();
    names[0] = "qualquer.pacote";
    assertThat(TrustedEventPackages.names()).containsExactly(
      "com.hotel.booking.system.commons.core.domain.event",
      "com.hotel.booking.system.commons.core.domain.event.customer");
  }

  @Test
  @DisplayName("preserva os campos herdados nos eventos de status do cliente")
  void preservesInheritedFieldsOfCustomerStatusEvents() {
    final var completed = CustomerBookingCompletedEvent.builder().reservationOrderId("order")
      .customerId("customer").status(CustomerReservationStatus.RESERVED).build();
    final var initiated = CustomerBookingInitiatedEvent.builder().reservationOrderId("order")
      .customerId("customer").status(CustomerReservationStatus.AWAITING_RESERVATION).build();
    final var paymentCompleted = CustomerBookingPaymentCompletedEvent.builder().reservationOrderId("order")
      .customerId("customer").status(CustomerReservationStatus.RESERVED).build();
    final var paymentRequested = CustomerBookingPaymentRequestedEvent.builder().reservationOrderId("order")
      .customerId("customer").status(CustomerReservationStatus.AWAITING_PAYMENT).build();
    final var paymentFailed = CustomerBookingPaymentFailedEvent.builder().reservationOrderId("order")
      .customerId("customer").status(CustomerReservationStatus.PAYMENT_FAILED).failureMessages(List.of("declined")).build();
    final var rejected = CustomerBookingRejectedEvent.builder().reservationOrderId("order")
      .customerId("customer").status(CustomerReservationStatus.RESERVATION_FAILED).failureMessages(List.of("unavailable")).build();
    assertThat(List.of(completed, initiated, paymentCompleted, paymentRequested, paymentFailed, rejected))
      .allSatisfy(event -> {
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getReservationOrderId()).isEqualTo("order");
        assertThat(event.getCustomerId()).isEqualTo("customer");
      });
    assertThat(paymentFailed.getFailureMessages()).containsExactly("declined");
    assertThat(rejected.getFailureMessages()).containsExactly("unavailable");
  }

  @TestFactory
  @DisplayName("toda classe concreta de evento tem um creator utilizável")
  Stream<DynamicTest> todaClasseConcretaDeEventoEhConstruivel() throws IOException {
    return this.classesDeEvento().stream()
      .map(tipo -> DynamicTest.dynamicTest(
        tipo.getSimpleName(),
        () -> assertThatCode(() -> MAPPER.readValue("{}", tipo))
          .describedAs(
            "%s não é construível pelo Jackson. Classes com @SuperBuilder precisam de "
              + "@Jacksonized — sem ele o Lombok gera um segundo construtor, o Jackson "
              + "não desempata e a mensagem é recusada no listener, não no arranque.",
            tipo.getSimpleName())
          .doesNotThrowAnyException()));
  }

  /**
   * Descobre as classes varrendo o pacote, em vez de listá-las à mão, para que um evento
   * novo entre na cobertura sem ninguém precisar lembrar disso.
   * <p>
   * O critério é o pacote, e não {@code Event.class::isAssignableFrom}. O filtro por
   * interface seria o reflexo natural e deixaria justamente as classes erradas de fora:
   * {@code BookingRoomStatusUpdatedEvent} e {@code PaymentRequestedEvent} não implementam
   * {@code Event}, e é sob a primeira que vivem as duas subclasses que quebraram na
   * migração para o Jackson 3. A única exceção é nomeada.
   */
  private List<Class<?>> classesDeEvento() throws IOException {
    final var raiz = Path.of("target", "classes");
    final var pacote = raiz.resolve(Path.of("com", "hotel", "booking", "system",
      "commons", "core", "domain", "event"));

    try (var arquivos = Files.walk(pacote)) {
      return arquivos
        .filter(f -> f.toString().endsWith(".class"))
        .filter(f -> !f.getFileName().toString().contains("$"))
        .map(f -> raiz.relativize(f).toString()
          .replace(".class", "")
          .replace(java.io.File.separatorChar, '.'))
        .map(EventContractTest::carregar)
        .filter(t -> !t.isInterface())
        .filter(t -> !t.isEnum())
        .filter(t -> !Modifier.isAbstract(t.getModifiers()))
        .filter(t -> !TrustedEventPackages.class.equals(t))
        .toList();
    }
  }

  private static Class<?> carregar(final String nome) {
    try {
      return Class.forName(nome);
    }
    catch (final ClassNotFoundException e) {
      throw new IllegalStateException("Classe compilada mas não carregável: " + nome, e);
    }
  }

}
