package com.hotel.booking.system.booking.service.infrastructure.db;

import com.hotel.booking.system.booking.service.core.application.mapper.BookingUseCaseMapperImpl;
import com.hotel.booking.system.booking.service.core.application.messaging.BookingRoomRequestedHandlerImpl;
import com.hotel.booking.system.booking.service.core.application.service.BookingInitializer;
import com.hotel.booking.system.booking.service.core.application.service.VerifyRoomAvailability;
import com.hotel.booking.system.booking.service.core.application.usecase.BookingRoomUseCaseImpl;
import com.hotel.booking.system.booking.service.infrastructure.db.entity.RoomEntity;
import com.hotel.booking.system.booking.service.infrastructure.db.mapper.impl.BookingDatabaseMapperImpl;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.RoomJpaRepository;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters.BookingRepositoryAdapter;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters.RoomRepositoryAdapter;
import com.hotel.booking.system.booking.service.infrastructure.messaging.listener.BookingRoomRequestedRabbitMQListener;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.ExchangeProperties;
import com.hotel.booking.system.booking.service.infrastructure.messaging.properties.RoutingKeyProperties;
import com.hotel.booking.system.booking.service.infrastructure.messaging.publisher.BookingRoomResponsePublisherImpl;
import com.hotel.booking.system.commons.core.application.idempotency.IdempotentEventConsumer;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomItemRepresentation;
import com.hotel.booking.system.commons.core.domain.event.BookingRoomRequestedEvent;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;
import com.hotel.booking.system.commons.infrastructure.messaging.idempotency.JdbcIdempotencyConfiguration;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.JacksonOutbox;
import com.hotel.booking.system.commons.infrastructure.messaging.outbox.JdbcOutboxRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ordem dos locks e limite de espera (issue #56).
 *
 * <p>Os dois quartos têm ids fixos, com {@code roomA} menor que {@code roomB}, e são gravados na
 * ordem inversa ({@code roomB} primeiro). Assim a ordem física das linhas no heap não coincide
 * com a ordem de id: o único jeito de o PostgreSQL travar {@code roomA} antes de {@code roomB} é
 * o {@code order by} da consulta.</p>
 */
@Import({
  BookingRepositoryAdapter.class,
  RoomRepositoryAdapter.class,
  BookingDatabaseMapperImpl.class,
  JdbcIdempotencyConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Lock dos quartos")
class RoomLockIT extends AbstractDatabaseIT {

  private static final LocalDate CHECK_IN = LocalDate.of(2043, 8, 1);

  @Autowired
  private RoomRepositoryAdapter rooms;

  @Autowired
  private RoomJpaRepository roomJpaRepository;

  @Autowired
  private BookingRepositoryAdapter bookings;

  @Autowired
  private BookingDatabaseMapperImpl mapper;

  @Autowired
  private IdempotentEventConsumer consumer;

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private JdbcClient jdbc;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction;

  private UUID roomA;

  private UUID roomB;

  @BeforeEach
  void criaQuartos() {
    this.transaction = new TransactionTemplate(this.transactionManager);
    this.roomA = UUID.fromString("10000000-0000-4000-8000-00000000000a");
    this.roomB = UUID.fromString("20000000-0000-4000-8000-00000000000b");
    this.transaction.executeWithoutResult(status -> {
      this.persistRoom(this.roomB);
      this.persistRoom(this.roomA);
    });
  }

  @AfterEach
  void apaga() {
    this.transaction.executeWithoutResult(status -> {
      this.jdbc.sql("delete from booking_room where room_id in (:a, :b)")
        .param("a", this.roomA.toString()).param("b", this.roomB.toString()).update();
      this.jdbc.sql("delete from booking where check_in = :checkIn").param("checkIn", CHECK_IN).update();
      this.jdbc.sql("delete from room where id in (:a, :b)")
        .param("a", this.roomA.toString()).param("b", this.roomB.toString()).update();
      this.jdbc.sql("delete from processed_event where event_type like '%BookingRoomRequestedEvent'").update();
      this.jdbc.sql("delete from outbox_event where exchange = :exchange").param("exchange", "exchange.lock-it").update();
    });
  }

  @Test
  @DisplayName("dois pedidos com os mesmos quartos em ordens opostas terminam sem deadlock")
  void ordensOpostasNaoEntramEmDeadlock() throws Exception {
    final var start = new CyclicBarrier(2);
    final var failures = new AtomicInteger();
    final var executor = Executors.newFixedThreadPool(2);
    try {
      executor.submit(() -> this.lockInOrder(start, failures, this.roomA, this.roomB));
      executor.submit(() -> this.lockInOrder(start, failures, this.roomB, this.roomA));
      executor.shutdown();
      assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    } finally {
      if (!executor.isShutdown()) {
        executor.shutdownNow();
      }
    }
    assertThat(failures).hasValue(0);
  }

  /**
   * Prova o {@code order by}, e não só a ausência de deadlock: um pedido que pede {@code roomB}
   * e depois {@code roomA} fica esperando o menor, que outra transação segura, e enquanto
   * espera <em>não</em> pode já ter o maior. Uma terceira conexão tenta o lock do maior sem
   * esperar: ela só consegue se o pedido em espera ainda não o tomou. Sem o {@code order by} o
   * PostgreSQL percorre as linhas na ordem física ({@code roomB} primeiro) e o pedido em espera
   * já segura {@code roomB}.
   */
  @Test
  @DisplayName("trava os quartos em ordem de id: quem espera o menor ainda não segura o maior")
  void quemEsperaOMenorAindaNaoSeguraOMaior() throws Exception {
    final var holding = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var holder = Executors.newSingleThreadExecutor();
    final var waiter = Executors.newSingleThreadExecutor();
    try {
      holder.submit(() -> this.transaction.executeWithoutResult(status -> {
        this.rooms.lockAllByRoomId(List.of(RoomId.of(this.roomA)));
        holding.countDown();
        this.await(release);
      }));
      assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
      final var waiting = waiter.submit(() -> this.transaction.executeWithoutResult(status ->
        this.rooms.lockAllByRoomId(List.of(RoomId.of(this.roomB), RoomId.of(this.roomA)))
      ));
      this.awaitSomeoneBlockedOnALock();

      assertThatCode(() -> this.transaction.executeWithoutResult(status ->
        this.jdbc.sql("select id from room where id = :id for no key update nowait")
          .param("id", this.roomB.toString())
          .query(String.class)
          .single()
      )).as("o pedido em espera ainda não tomou o lock do quarto de id maior").doesNotThrowAnyException();

      release.countDown();
      waiting.get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      holder.shutdownNow();
      waiter.shutdownNow();
    }
  }

  /**
   * Prova o valor e o escopo do {@code lock_timeout}: o adaptador padrão usa os 10 s do
   * {@code application.yml}, o impaciente os 300 ms que recebeu, e o limite some com a transação
   * (o {@code set_config} é local), em vez de ficar na conexão devolvida ao pool.
   */
  @Test
  @DisplayName("limita a espera em 10 s com o adaptador padrão, 300 ms com o impaciente, e só dentro da transação")
  void lockTimeoutTemOValorConfiguradoESoVaiAteOFimDaTransacao() {
    final var impatient = this.impatient();

    final var padrao = this.transaction.execute(status -> {
      this.rooms.lockAllByRoomId(List.of(RoomId.of(this.roomA)));
      return this.lockTimeoutDaSessao();
    });
    final var curto = this.transaction.execute(status -> {
      impatient.lockAllByRoomId(List.of(RoomId.of(this.roomA)));
      return this.lockTimeoutDaSessao();
    });
    final var depoisDoCommit = this.transaction.execute(status -> this.lockTimeoutDaSessao());

    assertThat(padrao).isEqualTo("10s");
    assertThat(curto).isEqualTo("300ms");
    assertThat(depoisDoCommit).as("o limite não vaza para a conexão que volta ao pool").isEqualTo("0");
  }

  /**
   * O estouro do limite atravessa a cadeia inteira: listener, consumidor idempotente, handler, caso
   * de uso, publisher do outbox e repositórios reais. A exceção chega a quem chamou como
   * {@code CannotAcquireLockException}, o tipo que {@code BookingApplicationTest} simula e que
   * deixa o retry e a DLQ agirem; se alguém a engolisse, a transação marcada para rollback
   * falharia no commit com outro tipo. A transação desfaz tudo o que já tinha gravado, o
   * registro do evento, e não sobra reserva nem resposta de falha de negócio no outbox.
   */
  @Test
  @DisplayName("o estouro do limite atravessa o listener: sem reserva, sem registro do evento e sem resposta no outbox")
  void estouroDoLimiteAtravessaOListenerSemGravarNada() throws Exception {
    final var holding = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var holder = Executors.newSingleThreadExecutor();
    holder.submit(() -> this.transaction.executeWithoutResult(status -> {
      this.rooms.lockAllByRoomId(List.of(RoomId.of(this.roomA)));
      holding.countDown();
      this.await(release);
    }));
    assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
    final var listener = this.listenerComEspera(this.impatient());
    final var event = this.eventOf(this.roomA);

    assertThatThrownBy(() -> this.transaction.executeWithoutResult(status -> listener.listen(event)))
      .isInstanceOf(CannotAcquireLockException.class)
      .hasStackTraceContaining("lock timeout");

    release.countDown();
    holder.shutdown();
    assertThat(holder.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    assertThat(this.count("select count(*) from booking where reservation_order_id = :id", event.getReservationOrderId()))
      .as("a reserva não foi gravada").isZero();
    assertThat(this.count("select count(*) from processed_event where event_id = :id", event.getEventId()))
      .as("o registro do evento foi desfeito").isZero();
    assertThat(this.count("select count(*) from outbox_event where aggregate_id = :id", event.getReservationOrderId()))
      .as("nenhuma resposta, nem de falha de negócio, foi gravada no outbox").isZero();
  }

  private BookingRoomRequestedRabbitMQListener listenerComEspera(final RoomRepositoryAdapter roomRepository) {
    final var useCaseMapper = new BookingUseCaseMapperImpl();
    final var useCase = new BookingRoomUseCaseImpl(
      this.bookings,
      useCaseMapper,
      new VerifyRoomAvailability(this.bookings, roomRepository),
      new BookingInitializer()
    );
    final var publisher = new BookingRoomResponsePublisherImpl(
      new JacksonOutbox(new JdbcOutboxRepository(this.jdbc), JsonMapper.builder().build()),
      new RoutingKeyProperties("request", "response", "change"),
      new ExchangeProperties("exchange.lock-it")
    );
    return new BookingRoomRequestedRabbitMQListener(
      new BookingRoomRequestedHandlerImpl(useCase, useCaseMapper, publisher),
      this.consumer
    );
  }

  private RoomRepositoryAdapter impatient() {
    return new RoomRepositoryAdapter(
      this.roomJpaRepository,
      this.mapper,
      this.entityManager,
      Duration.ofMillis(300)
    );
  }

  private BookingRoomRequestedEvent eventOf(final UUID room) {
    return BookingRoomRequestedEvent.builder()
      .reservationOrderId(UUID.randomUUID().toString())
      .customerId(UUID.randomUUID().toString())
      .price(new BigDecimal("10.0000"))
      .guests(2)
      .checkIn(CHECK_IN)
      .checkOut(CHECK_IN.plusDays(2))
      .rooms(List.of(BookingRoomItemRepresentation.builder()
        .roomId(room.toString())
        .quantity(1)
        .price(new BigDecimal("10.0000"))
        .build()))
      .build();
  }

  private String lockTimeoutDaSessao() {
    return this.jdbc.sql("select current_setting('lock_timeout')").query(String.class).single();
  }

  private long count(final String sql, final String id) {
    return this.jdbc.sql(sql).param("id", id).query(Long.class).single();
  }

  /** Espera a transação do pedido que pede o menor quarto ficar bloqueada num lock de linha. */
  private void awaitSomeoneBlockedOnALock() throws InterruptedException {
    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      final var blocked = this.jdbc.sql("select count(*) from pg_stat_activity where wait_event_type = 'Lock'")
        .query(Long.class)
        .single();
      if (blocked > 0) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("nenhuma transação ficou esperando o lock do quarto");
  }

  private void await(final CountDownLatch release) {
    try {
      assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private void lockInOrder(final CyclicBarrier start, final AtomicInteger failures, final UUID first, final UUID second) {
    try {
      start.await(5, TimeUnit.SECONDS);
      this.transaction.executeWithoutResult(status ->
        this.rooms.lockAllByRoomId(List.of(RoomId.of(first), RoomId.of(second)))
      );
    } catch (final Exception exception) {
      failures.incrementAndGet();
    }
  }

  private void persistRoom(final UUID id) {
    final var room = new RoomEntity();
    room.setId(id);
    room.setHotelId(UUID.randomUUID());
    room.setCurrentPrice(new BigDecimal("10.0000"));
    room.setCapacity(2);
    room.setQuantity(4);
    this.entityManager.persist(room);
  }

}
