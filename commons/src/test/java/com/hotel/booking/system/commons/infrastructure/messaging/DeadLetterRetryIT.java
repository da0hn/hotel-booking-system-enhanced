package com.hotel.booking.system.commons.infrastructure.messaging;

import com.hotel.booking.system.commons.core.domain.event.PaymentCompletedEvent;
import com.hotel.booking.system.commons.core.domain.event.PaymentResponseEvent;
import com.hotel.booking.system.commons.core.domain.exception.UnsupportedEventTypeException;
import com.hotel.booking.system.commons.core.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Prova, contra um RabbitMQ de verdade, o que a topologia e a configuração de retry do
 * {@code commons} prometem aos quatro serviços: uma falha de listener tem número finito de
 * tentativas, termina numa DLQ que guarda o motivo, e nunca volta ao início da fila
 * principal.
 * <p>
 * O contexto é mínimo de propósito — só o auto-configurador do Rabbit, a configuração
 * compartilhada e um listener de sonda —, mas a propriedade de retry usa os mesmos nomes do
 * {@code application.yml} dos serviços, com intervalos curtos para o teste não esperar.
 * <p>
 * O listener recebe {@link PaymentResponseEvent}, uma classe abstrata, como os handlers de
 * resposta do {@code hotel-service}: com um tipo concreto o conversor ignoraria o
 * {@code __TypeId__} da mensagem, e os cenários de tipo recusado não exerceriam nada.
 */
@SpringBootTest(classes = DeadLetterRetryIT.Topologia.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Retry e dead-letter contra um RabbitMQ real")
class DeadLetterRetryIT {

  private static final String EXCHANGE = "it.payment";

  private static final String QUEUE = "it.payment-confirmation";

  private static final String ROUTING_KEY = "it.routing-key.payment-confirmation";

  private static final String DEAD_LETTER_QUEUE = DeadLetterTopology.deadLetterQueueName(QUEUE);

  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  private static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:3-management");

  static {
    BROKER.start();
  }

  @DynamicPropertySource
  static void configuraBrokerERetry(final DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", BROKER::getHost);
    registry.add("spring.rabbitmq.port", BROKER::getAmqpPort);
    registry.add("spring.rabbitmq.username", BROKER::getAdminUsername);
    registry.add("spring.rabbitmq.password", BROKER::getAdminPassword);
    registry.add("spring.rabbitmq.listener.simple.default-requeue-rejected", () -> "false");
    registry.add("spring.rabbitmq.listener.simple.retry.enabled", () -> "true");
    registry.add("spring.rabbitmq.listener.simple.retry.max-retries", () -> "2");
    registry.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "50ms");
    registry.add("spring.rabbitmq.listener.simple.retry.multiplier", () -> "2.0");
    registry.add("spring.rabbitmq.listener.simple.retry.max-interval", () -> "200ms");
    registry.add(DeadLetterRetryConfiguration.JITTER_PROPERTY, () -> "200ms");
  }

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @Autowired
  private Sonda sonda;

  @Autowired
  private ConversorContador conversor;

  @BeforeEach
  void limpaFilasEZeraContadores() {
    this.rabbitAdmin.purgeQueue(QUEUE, false);
    this.rabbitAdmin.purgeQueue(DEAD_LETTER_QUEUE, false);
    this.sonda.reinicia();
    this.conversor.conversoes.set(0);
  }

  @Test
  @DisplayName("consome o evento válido na primeira tentativa, sem tocar na DLQ")
  void consomeEventoValido() {
    this.envia(pagamento("pedido-valido"));

    await().atMost(TIMEOUT).untilAsserted(() -> assertThat(this.sonda.processados).hasSize(1));

    assertThat(this.sonda.tentativas).hasValue(1);
    assertThat(this.rabbitTemplate.receive(DEAD_LETTER_QUEUE)).isNull();
  }

  @Test
  @DisplayName("retenta a falha transitória e processa na segunda tentativa, sem ir para a DLQ")
  void retentaFalhaTransitoria() {
    final var falhas = new AtomicInteger();
    this.sonda.comportamento = evento -> {
      if (falhas.getAndIncrement() == 0) {
        throw new IllegalStateException("banco indisponível");
      }
    };

    this.envia(pagamento("pedido-transitorio"));

    await().atMost(TIMEOUT).untilAsserted(() -> assertThat(this.sonda.processados).hasSize(1));
    assertThat(this.sonda.tentativas).hasValue(2);
    assertThat(this.rabbitTemplate.receive(DEAD_LETTER_QUEUE)).isNull();
  }

  @Test
  @DisplayName("esgota as 3 tentativas, entrega na DLQ com o motivo e não volta para a fila principal")
  void enviaParaADlqDepoisDeEsgotarAsTentativas() {
    this.sonda.comportamento = evento -> {
      throw new IllegalStateException("falha permanente");
    };

    this.envia(pagamento("pedido-permanente"));

    final var morta = this.aguardaNaDlq();
    final var headers = morta.getMessageProperties().getHeaders();
    assertThat(headers)
      .containsEntry("x-exception-message", "falha permanente")
      .containsEntry("x-original-exchange", EXCHANGE)
      .containsEntry("x-original-routingKey", ROUTING_KEY)
      .containsKey("x-exception-stacktrace");
    assertThat(morta.getMessageProperties().getHeaders().get("x-exception-stacktrace").toString())
      .contains("IllegalStateException", "falha permanente");

    await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
      assertThat(this.sonda.tentativas).hasValue(3);
      assertThat(this.rabbitAdmin.getQueueInfo(QUEUE).getMessageCount()).isZero();
    });
  }

  @Test
  @DisplayName("preserva o corpo original da mensagem na DLQ")
  void preservaOCorpoOriginal() {
    this.sonda.comportamento = evento -> {
      throw new IllegalStateException("falha permanente");
    };

    this.envia(pagamento("pedido-corpo"));

    final var morta = this.aguardaNaDlq();
    assertThat(new String(morta.getBody(), UTF_8)).contains("pedido-corpo");
    assertThat(morta.getMessageProperties().getHeaders().get("__TypeId__"))
      .isEqualTo(PaymentCompletedEvent.class.getName());
  }

  @Test
  @DisplayName("mensagem venenosa não bloqueia as seguintes na fila principal")
  void naoBloqueiaAsMensagensSeguintes() {
    this.sonda.comportamento = evento -> {
      if ("veneno".equals(((PaymentCompletedEvent) evento).getReservationOrderId())) {
        throw new IllegalStateException("falha permanente");
      }
    };

    this.envia(pagamento("veneno"));
    this.envia(pagamento("saudavel"));

    await().atMost(TIMEOUT).untilAsserted(() -> assertThat(this.sonda.processados)
      .extracting(evento -> ((PaymentCompletedEvent) evento).getReservationOrderId())
      .containsExactly("saudavel"));
    assertThat(this.aguardaNaDlq().getMessageProperties().getHeaders())
      .containsEntry("x-exception-message", "falha permanente");
  }

  @Test
  @DisplayName("subtipo sem tratamento vai para a DLQ na primeira tentativa, sem retentativa")
  void enviaSubtipoSemTratamentoDiretoParaADlq() {
    this.sonda.comportamento = evento -> {
      throw new UnsupportedEventTypeException(PaymentResponseEvent.class, evento);
    };

    this.envia(pagamento("pedido-subtipo"));

    final var morta = this.aguardaNaDlq();
    assertThat(this.sonda.tentativas).hasValue(1);
    assertThat(morta.getMessageProperties().getHeaders().get("x-exception-message").toString())
      .contains("Unknown event");
  }

  @Test
  @DisplayName("JSON malformado vai para a DLQ sem retentativa e sem chegar ao listener")
  void enviaJsonMalformadoDiretoParaADlq() {
    this.enviaBruto("{isto não é json".getBytes(UTF_8), PaymentCompletedEvent.class.getName());

    final var morta = this.aguardaNaDlq();
    assertThat(this.sonda.tentativas).hasValue(0);
    assertThat(this.conversor.conversoes).hasValue(1);
    assertThat(morta.getMessageProperties().getHeaders()).containsKey("x-exception-message");
  }

  @Test
  @DisplayName("tipo fora dos pacotes confiáveis vai para a DLQ sem retentativa e sem chegar ao listener")
  void enviaTipoNaoConfiavelDiretoParaADlq() {
    this.enviaBruto("{}".getBytes(UTF_8), "com.invasor.Gadget");

    final var morta = this.aguardaNaDlq();
    assertThat(this.sonda.tentativas).hasValue(0);
    assertThat(this.conversor.conversoes).hasValue(1);
    assertThat(morta.getMessageProperties().getHeaders()).containsKey("x-exception-message");
  }

  @Test
  @DisplayName("classe de evento inexistente vai para a DLQ sem retentativa e sem chegar ao listener")
  void enviaClasseInexistenteDiretoParaADlq() {
    this.enviaBruto("{}".getBytes(UTF_8), PaymentCompletedEvent.class.getPackageName() + ".EventoQueNaoExiste");

    final var morta = this.aguardaNaDlq();
    assertThat(this.sonda.tentativas).hasValue(0);
    assertThat(this.conversor.conversoes).hasValue(1);
    assertThat(morta.getMessageProperties().getHeaders()).containsKey("x-exception-message");
  }

  /**
   * Com 50 ms de intervalo inicial, 200 ms de jitter e teto de 200 ms, a primeira espera é
   * sorteada entre 50 e 200 ms. Sem jitter ela seria sempre 50 ms mais o ruído do agendador, e
   * é esse piso que o teste usa para notar o sorteio: dezesseis mensagens, e basta uma esperar
   * mais de 100 ms. A chance de nenhuma passar disso com o jitter ligado é de cerca de
   * {@code (1/3)^16}.
   */
  @Test
  @DisplayName("espalha a espera entre as tentativas quando há jitter configurado")
  void espalhaAEsperaEntreAsTentativas() {
    final var primeirasEsperasEmMs = new ArrayList<Long>();

    for (var i = 0; i < 16; i++) {
      this.sonda.reinicia();
      this.sonda.comportamento = evento -> {
        throw new IllegalStateException("falha permanente");
      };
      this.envia(pagamento("pedido-jitter-" + i));
      this.aguardaNaDlq();
      primeirasEsperasEmMs.add(this.sonda.esperaEntreTentativasEmMs(0));
    }

    assertThat(primeirasEsperasEmMs).allSatisfy(espera -> assertThat(espera).isGreaterThanOrEqualTo(50L));
    assertThat(primeirasEsperasEmMs).anyMatch(espera -> espera > 100L);
  }

  private void envia(final PaymentResponseEvent evento) {
    this.rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, evento);
  }

  private void enviaBruto(final byte[] corpo, final String tipo) {
    final var mensagem = MessageBuilder.withBody(corpo)
      .setContentType("application/json")
      .setHeader("__TypeId__", tipo)
      .build();
    this.rabbitTemplate.send(EXCHANGE, ROUTING_KEY, mensagem);
  }

  private Message aguardaNaDlq() {
    final var recebida = new AtomicReference<Message>();
    await().atMost(TIMEOUT).untilAsserted(() -> {
      recebida.set(this.rabbitTemplate.receive(DEAD_LETTER_QUEUE));
      assertThat(recebida.get()).as("mensagem na DLQ").isNotNull();
    });
    return recebida.get();
  }

  private static PaymentCompletedEvent pagamento(final String reservationOrderId) {
    return PaymentCompletedEvent.builder()
      .reservationOrderId(reservationOrderId)
      .customerId("customer")
      .totalPrice(new BigDecimal("10.0000"))
      .status(PaymentStatus.COMPLETED)
      .build();
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration(RabbitAutoConfiguration.class)
  @Import(DeadLetterRetryConfiguration.class)
  static class Topologia {

    @Bean
    ConversorContador messageConverter() {
      return new ConversorContador(JsonMapper.builder().build());
    }

    @Bean
    DirectExchange paymentExchange() {
      return new DirectExchange(EXCHANGE);
    }

    @Bean
    Queue paymentConfirmationQueue() {
      return DeadLetterTopology.queue(QUEUE);
    }

    @Bean
    Binding paymentConfirmationBinding(final DirectExchange paymentExchange, final Queue paymentConfirmationQueue) {
      return BindingBuilder.bind(paymentConfirmationQueue).to(paymentExchange).with(ROUTING_KEY);
    }

    @Bean
    Declarables deadLetterDeclarables() {
      return DeadLetterTopology.declarables(QUEUE);
    }

    @Bean
    Sonda sonda() {
      return new Sonda();
    }

  }

  /**
   * Conta quantas vezes o conversor foi chamado para desserializar, o que distingue uma
   * falha tentada uma vez de uma tentada três: o listener não chega a ser invocado quando
   * a mensagem nem se converte.
   */
  static class ConversorContador extends EventMessageConverter {

    private final AtomicInteger conversoes = new AtomicInteger();

    ConversorContador(final JsonMapper jsonMapper) {
      super(jsonMapper);
    }

    @Override
    public Object fromMessage(final Message message, final Object conversionHint) {
      this.conversoes.incrementAndGet();
      return super.fromMessage(message, conversionHint);
    }

  }

  public static class Sonda {

    private final AtomicInteger tentativas = new AtomicInteger();

    private final List<PaymentResponseEvent> processados = new CopyOnWriteArrayList<>();

    private final List<Long> instantesEmNanos = new CopyOnWriteArrayList<>();

    private volatile Consumer<PaymentResponseEvent> comportamento = evento -> {
    };

    void reinicia() {
      this.tentativas.set(0);
      this.processados.clear();
      this.instantesEmNanos.clear();
      this.comportamento = evento -> {
      };
    }

    /** O intervalo, em ms, entre a tentativa {@code indice} e a seguinte. */
    long esperaEntreTentativasEmMs(final int indice) {
      return Duration.ofNanos(this.instantesEmNanos.get(indice + 1) - this.instantesEmNanos.get(indice)).toMillis();
    }

    @RabbitListener(queues = QUEUE)
    public void escuta(final PaymentResponseEvent evento) {
      this.instantesEmNanos.add(System.nanoTime());
      this.tentativas.incrementAndGet();
      this.comportamento.accept(evento);
      this.processados.add(evento);
    }

  }

}
