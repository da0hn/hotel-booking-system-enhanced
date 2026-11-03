package com.hotel.booking.system.booking.service.infrastructure.db;

import com.hotel.booking.system.booking.service.core.application.dto.BookingRoomInput;
import com.hotel.booking.system.booking.service.core.application.dto.BookingRoomItemInput;
import com.hotel.booking.system.booking.service.core.application.dto.BookingRoomOutput;
import com.hotel.booking.system.booking.service.core.application.mapper.BookingUseCaseMapperImpl;
import com.hotel.booking.system.booking.service.core.application.service.BookingInitializer;
import com.hotel.booking.system.booking.service.core.application.service.VerifyRoomAvailability;
import com.hotel.booking.system.booking.service.core.application.usecase.BookingRoomUseCaseImpl;
import com.hotel.booking.system.booking.service.core.domain.entity.Booking;
import com.hotel.booking.system.booking.service.core.domain.entity.BookingPeriod;
import com.hotel.booking.system.booking.service.core.ports.spi.repository.BookingRepository;
import com.hotel.booking.system.booking.service.infrastructure.db.entity.RoomEntity;
import com.hotel.booking.system.booking.service.infrastructure.db.mapper.impl.BookingDatabaseMapperImpl;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters.BookingRepositoryAdapter;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.adapters.RoomRepositoryAdapter;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.commons.core.domain.valueobject.RoomId;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova que duas reservas simultâneas do último quarto não ficam as duas confirmadas
 * (issue #5).
 *
 * <p>O caso de uso roda de verdade — verificação de disponibilidade, gravação, adaptadores e
 * PostgreSQL —, cada chamada na sua própria transação e na sua própria thread, como o listener
 * do RabbitMQ faria com dois consumidores. Só o broker fica de fora.</p>
 *
 * <p><strong>A corrida é forçada, e não esperada por sorte.</strong> O {@link Rendezvous}
 * segura cada thread logo depois de ela ler a ocupação do quarto até que a outra também tenha
 * lido. Sem proteção nenhuma, as duas leem "quarto livre" e as duas gravam, sempre. Com o lock
 * no quarto a segunda thread nem chega à leitura enquanto a primeira não termina; o ponto de
 * encontro então expira — é por isso que ele tem um limite de espera — e a primeira segue
 * sozinha.</p>
 *
 * <p>{@code NOT_SUPPORTED} desliga a transação que o {@code @DataJpaTest} abriria em volta do
 * teste: duas threads não enxergam a mesma transação, e o que se quer observar é justamente o
 * que cada uma comita. Como o contêiner é compartilhado pelas demais classes de IT, o quarto
 * nasce com um id aleatório e tudo que o teste gravou é apagado ao fim.</p>
 */
@Import({BookingRepositoryAdapter.class, RoomRepositoryAdapter.class, BookingDatabaseMapperImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Disponibilidade de quartos sob concorrência")
class RoomAvailabilityConcurrencyIT extends AbstractDatabaseIT {

  private static final LocalDate CHECK_IN = LocalDate.of(2040, 3, 1);

  private static final LocalDate CHECK_OUT = LocalDate.of(2040, 3, 4);

  private static final BigDecimal PRICE = new BigDecimal("100.0000");

  private static final long RENDEZVOUS_TIMEOUT_MILLIS = 3_000;

  @Autowired
  private BookingRepositoryAdapter bookings;

  @Autowired
  private RoomRepositoryAdapter rooms;

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction;

  private UUID roomId;

  @BeforeEach
  void insertsRoomWithASingleUnit() {
    this.transaction = new TransactionTemplate(this.transactionManager);
    this.roomId = UUID.randomUUID();
    this.transaction.executeWithoutResult(status -> {
      final var room = new RoomEntity();
      room.setId(this.roomId);
      room.setHotelId(UUID.randomUUID());
      room.setCurrentPrice(PRICE);
      room.setCapacity(2);
      room.setQuantity(1);
      this.entityManager.persist(room);
    });
  }

  @AfterEach
  void removesWhatTheTestCommitted() {
    this.transaction.executeWithoutResult(status -> {
      final var bookingIds = this.entityManager
        .createNativeQuery("select booking_id from booking_room where room_id = :room")
        .setParameter("room", this.roomId.toString())
        .getResultList();
      this.entityManager.createNativeQuery("delete from booking_room where room_id = :room")
        .setParameter("room", this.roomId.toString())
        .executeUpdate();
      bookingIds.forEach(id -> this.entityManager.createNativeQuery("delete from booking where id = :id")
        .setParameter("id", id)
        .executeUpdate());
      this.entityManager.createNativeQuery("delete from room where id = :room")
        .setParameter("room", this.roomId.toString())
        .executeUpdate();
    });
  }

  @Test
  @DisplayName("confirma só uma de duas reservas simultâneas do último quarto")
  void confirmsOnlyOneOfTwoSimultaneousBookingsOfTheLastRoom() throws Exception {
    final var rendezvous = new Rendezvous(this.bookings, RENDEZVOUS_TIMEOUT_MILLIS);
    final var useCase = new BookingRoomUseCaseImpl(
      rendezvous,
      new BookingUseCaseMapperImpl(),
      new VerifyRoomAvailability(rendezvous, this.rooms),
      new BookingInitializer()
    );

    final var outputs = this.runConcurrently(useCase, this.requestForTheSingleUnit(), this.requestForTheSingleUnit());

    assertThat(this.unitsPersisted())
      .as("unidades gravadas para um quarto que só tem uma: o que passar disso é overbooking")
      .isEqualTo(1);
    assertThat(outputs)
      .extracting(BookingRoomOutput::status)
      .containsExactlyInAnyOrder(CustomerReservationStatus.AWAITING_PAYMENT, CustomerReservationStatus.RESERVATION_FAILED);
  }

  private BookingRoomInput requestForTheSingleUnit() {
    return BookingRoomInput.builder()
      .reservationOrderId(UUID.randomUUID().toString())
      .customerId(UUID.randomUUID().toString())
      .price(PRICE)
      .guests(2)
      .checkIn(CHECK_IN)
      .checkOut(CHECK_OUT)
      .rooms(List.of(new BookingRoomItemInput(this.roomId.toString(), 1, PRICE)))
      .build();
  }

  private List<BookingRoomOutput> runConcurrently(
    final BookingRoomUseCaseImpl useCase,
    final BookingRoomInput first,
    final BookingRoomInput second
  ) throws InterruptedException, ExecutionException, TimeoutException {
    final var pool = Executors.newFixedThreadPool(2);
    try {
      final var start = new CountDownLatch(1);
      final var futures = List.of(first, second).stream()
        .map(input -> pool.submit(() -> {
          start.await();
          return this.transaction.execute(status -> useCase.execute(input));
        }))
        .toList();
      start.countDown();
      final var outputs = new ArrayList<BookingRoomOutput>();
      for (final var future : futures) {
        outputs.add(future.get(30, TimeUnit.SECONDS));
      }
      return outputs;
    } finally {
      pool.shutdownNow();
    }
  }

  private long unitsPersisted() {
    return this.transaction.execute(status -> ((Number) this.entityManager
      .createNativeQuery("select coalesce(sum(quantity), 0) from booking_room where room_id = :room")
      .setParameter("room", this.roomId.toString())
      .getSingleResult()).longValue());
  }

  /**
   * Decora o repositório de reservas para segurar cada thread depois da leitura de ocupação até
   * que a outra também tenha lido, ou até o tempo acabar.
   *
   * <p>Expirar não é erro: significa que a outra thread está impedida de chegar à leitura — no
   * código corrigido, esperando o lock do quarto —, e quem expirou segue sozinho. A barreira
   * quebrada deixa a thread seguinte passar direto, sem pagar a espera outra vez.</p>
   */
  private static final class Rendezvous implements BookingRepository {

    private final BookingRepository delegate;

    private final CyclicBarrier barrier = new CyclicBarrier(2);

    private final long timeoutMillis;

    private Rendezvous(final BookingRepository delegate, final long timeoutMillis) {
      this.delegate = delegate;
      this.timeoutMillis = timeoutMillis;
    }

    @Override
    public List<Booking> findBookingByRoomIdAndPeriod(final RoomId roomId, final BookingPeriod bookingPeriod) {
      final var found = this.delegate.findBookingByRoomIdAndPeriod(roomId, bookingPeriod);
      this.waitForTheOtherThread();
      return found;
    }

    @Override
    public void save(final Booking booking) {
      this.delegate.save(booking);
    }

    @Override
    public Optional<Booking> findBookingByReservationOrderId(final ReservationOrderId reservationOrderId) {
      return this.delegate.findBookingByReservationOrderId(reservationOrderId);
    }

    private void waitForTheOtherThread() {
      try {
        this.barrier.await(this.timeoutMillis, TimeUnit.MILLISECONDS);
      } catch (final TimeoutException | BrokenBarrierException e) {
        // a outra thread não chegou: está esperando o lock do quarto, e este segue sozinho
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

  }

}
