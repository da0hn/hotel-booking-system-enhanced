package com.hotel.booking.system.booking.service.infrastructure.db;

import com.hotel.booking.system.booking.service.core.domain.entity.BookingPeriod;
import com.hotel.booking.system.booking.service.infrastructure.db.entity.BookingEntity;
import com.hotel.booking.system.booking.service.infrastructure.db.entity.BookingRoomEntity;
import com.hotel.booking.system.booking.service.infrastructure.db.entity.RoomEntity;
import com.hotel.booking.system.booking.service.infrastructure.db.repository.BookingJpaRepository;
import com.hotel.booking.system.commons.core.domain.valueobject.BookingStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.hibernate.Session;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Exercita as consultas escritas à mão do {@code booking-service} que a reserva e a verificação de
 * disponibilidade usam: a soma de ocupação por quarto e a busca pelo pedido.
 *
 * <p>{@code sumOccupiedUnits} é o coração da regra de disponibilidade: é ela que decide
 * quantas unidades do quarto estão ocupadas no período pedido. Como compara colunas
 * {@code date} com desigualdades estritas, ela depende de como o banco compara datas — e é
 * exatamente o tipo de coisa que uma troca de engine muda em silêncio.</p>
 */
@DisplayName("Consultas de reserva por período")
class BookingJpaRepositoryIT extends AbstractDatabaseIT {

  /** Quarto Triplo Deluxe do Amazon Plaza; o seed o reserva de 15 a 25 de junho de 2023. */
  private static final UUID QUARTO_TRIPLO_DELUXE = UUID.fromString("2223dc04-831a-4bac-aef5-e22195575cc6");

  /** Quarto Standard do Taiamã; o seed o reserva duas vezes, em julho de 2023. */
  private static final UUID QUARTO_STANDARD_TAIAMA = UUID.fromString("2c5c52bb-739d-468b-a2a2-b8b917a2004f");

  @Autowired
  private BookingJpaRepository repository;

  @Autowired
  private EntityManager entityManager;

  @Test
  @DisplayName("acha a reserva quando o check-in cai dentro do período ocupado")
  void achaQuandoCheckInCaiDentro() {
    assertThat(this.occupied(QUARTO_TRIPLO_DELUXE, LocalDate.of(2023, 6, 20), LocalDate.of(2023, 6, 30))).isEqualTo(1);
  }

  @Test
  @DisplayName("acha a reserva quando o check-out cai dentro do período ocupado")
  void achaQuandoCheckOutCaiDentro() {
    assertThat(this.occupied(QUARTO_TRIPLO_DELUXE, LocalDate.of(2023, 6, 10), LocalDate.of(2023, 6, 20))).isEqualTo(1);
  }

  @Test
  @DisplayName("acha a reserva quando o período pedido cai inteiro dentro dela")
  void achaQuandoPeriodoCaiInteiroDentro() {
    assertThat(this.occupied(QUARTO_TRIPLO_DELUXE, LocalDate.of(2023, 6, 18), LocalDate.of(2023, 6, 20))).isEqualTo(1);
  }

  @Test
  @DisplayName("não trata o dia de troca como conflito: o check-out de uma reserva libera o check-in da outra")
  void naoTrataODiaDeTrocaComoConflito() {
    assertThat(this.occupied(QUARTO_TRIPLO_DELUXE, LocalDate.of(2023, 6, 25), LocalDate.of(2023, 6, 27)))
      .as("entra no dia em que a reserva sai")
      .isZero();
    assertThat(this.occupied(QUARTO_TRIPLO_DELUXE, LocalDate.of(2023, 6, 10), LocalDate.of(2023, 6, 15)))
      .as("sai no dia em que a reserva entra")
      .isZero();
  }

  @Test
  @DisplayName("devolve vazio quando o período não encosta em nenhuma reserva")
  void devolveVazioQuandoPeriodoNaoEncosta() {
    assertThat(this.occupied(QUARTO_TRIPLO_DELUXE, LocalDate.of(2023, 8, 1), LocalDate.of(2023, 8, 10))).isZero();
  }

  @Test
  @DisplayName("soma as unidades das duas reservas de julho, 1 e 2")
  void somaAsDuasReservasDeJulho() {
    assertThat(this.occupied(QUARTO_STANDARD_TAIAMA, LocalDate.of(2023, 7, 10), LocalDate.of(2023, 7, 15))).isEqualTo(3);
  }

  /**
   * Guarda a correção da lacuna do {@code between} (issue #5).
   *
   * <p>A cláusula antiga só testava se o check-in <em>ou</em> o check-out pedido caíam dentro
   * de uma reserva existente. Um período que <strong>contém</strong> a reserva inteira — chega
   * antes e sai depois — não casava com nenhuma das duas, e o quarto aparecia como livre num
   * intervalo em que está ocupado.</p>
   */
  @Test
  @DisplayName("acha a reserva quando o período pedido a engloba")
  void achaQuandoPeriodoEngloba() {
    assertThat(this.occupied(QUARTO_TRIPLO_DELUXE, LocalDate.of(2023, 6, 1), LocalDate.of(2023, 7, 31)))
      .as("o período engloba a reserva: o quarto está ocupado nesse intervalo")
      .isEqualTo(1);
  }

  /**
   * Reserva cancelada não ocupa unidade nenhuma, e elas só se acumulam (o pagamento recusado
   * cancela): a soma não pode contá-las. A reserva ativa do mesmo período conta uma vez.
   */
  @Test
  @DisplayName("ignora reserva cancelada na soma, mas conta a ativa do mesmo período")
  void ignoraReservaCanceladaNaSoma() {
    this.gravaReserva(BookingStatus.PENDING);
    this.gravaReserva(BookingStatus.CANCELED);
    this.entityManager.flush();
    this.entityManager.clear();

    assertThat(this.occupied(QUARTO_TRIPLO_DELUXE, LocalDate.of(2035, 3, 2), LocalDate.of(2035, 3, 4)))
      .isEqualTo(1);
  }

  /**
   * O pedido pede vários quartos de uma vez: a consulta devolve uma linha por quarto pedido, com
   * a soma de cada um, e nenhuma de um quarto que ninguém pediu, mesmo ocupado no período.
   */
  @Test
  @DisplayName("soma a ocupação de cada quarto pedido e não traz o quarto que não foi pedido")
  void somaAOcupacaoDeCadaQuartoPedidoSemTrazerOsOutros() {
    final var naoPedido = this.outroQuarto();
    this.gravaReserva(BookingStatus.PENDING, 2, QUARTO_TRIPLO_DELUXE);
    this.gravaReserva(BookingStatus.CONFIRMED, 3, QUARTO_STANDARD_TAIAMA);
    this.gravaReserva(BookingStatus.PENDING, 4, naoPedido);
    this.entityManager.flush();
    this.entityManager.clear();

    final var porQuarto = this.occupiedByRoom(
      List.of(QUARTO_TRIPLO_DELUXE, QUARTO_STANDARD_TAIAMA),
      LocalDate.of(2035, 3, 2),
      LocalDate.of(2035, 3, 4)
    );

    assertThat(porQuarto).containsOnly(entry(QUARTO_TRIPLO_DELUXE, 2), entry(QUARTO_STANDARD_TAIAMA, 3));
  }

  @Test
  @DisplayName("conta unidade exatamente quando BookingPeriod.overlaps diz que há conflito")
  void consultaEOverlapsConcordam() {
    final var existente = BookingPeriod.of(LocalDate.of(2023, 6, 15), LocalDate.of(2023, 6, 25));
    final var janelas = List.of(
      BookingPeriod.of(LocalDate.of(2023, 6, 20), LocalDate.of(2023, 6, 30)),
      BookingPeriod.of(LocalDate.of(2023, 6, 25), LocalDate.of(2023, 6, 27)),
      BookingPeriod.of(LocalDate.of(2023, 6, 10), LocalDate.of(2023, 6, 15)),
      BookingPeriod.of(LocalDate.of(2023, 6, 1), LocalDate.of(2023, 7, 31)),
      BookingPeriod.of(LocalDate.of(2023, 8, 1), LocalDate.of(2023, 8, 10))
    );
    for (final var janela : janelas) {
      final var contou = this.occupied(QUARTO_TRIPLO_DELUXE, janela.getCheckIn(), janela.getCheckOut()) > 0;
      assertThat(contou).isEqualTo(existente.overlaps(janela));
    }
  }

  @Test
  @DisplayName("soma as duas linhas do mesmo quarto uma vez cada")
  void somaDuasLinhasDoMesmoQuartoSemDuplicar() {
    final var reserva = this.gravaReserva(BookingStatus.PENDING, 2);
    final var segunda = BookingRoomEntity.builder()
      .id(UUID.randomUUID())
      .room(this.entityManager.getReference(RoomEntity.class, QUARTO_TRIPLO_DELUXE))
      .booking(this.entityManager.getReference(BookingEntity.class, reserva))
      .price(new BigDecimal("10.0000"))
      .quantity(3)
      .build();
    this.entityManager.persist(segunda);
    this.entityManager.flush();
    this.entityManager.clear();

    final var session = this.entityManager.unwrap(Session.class);
    final var statistics = session.getSessionFactory().getStatistics();
    statistics.setStatisticsEnabled(true);
    statistics.clear();

    assertThat(this.occupied(QUARTO_TRIPLO_DELUXE, LocalDate.of(2035, 3, 2), LocalDate.of(2035, 3, 4))).isEqualTo(5);
    assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
  }

  private int occupied(final UUID room, final LocalDate checkIn, final LocalDate checkOut) {
    return this.occupiedByRoom(List.of(room), checkIn, checkOut).getOrDefault(room, 0);
  }

  private Map<UUID, Integer> occupiedByRoom(final List<UUID> rooms, final LocalDate checkIn, final LocalDate checkOut) {
    final var porQuarto = new HashMap<UUID, Integer>();
    for (final var row : this.repository.sumOccupiedUnits(rooms, checkIn, checkOut)) {
      porQuarto.put((UUID) row[0], ((Number) row[1]).intValue());
    }
    return porQuarto;
  }

  /** Um quarto do seed que não é nenhum dos dois constantes desta classe. */
  private UUID outroQuarto() {
    return UUID.fromString(String.valueOf(this.entityManager
      .createNativeQuery("select id from room where id not in (:triplo, :standard) limit 1")
      .setParameter("triplo", QUARTO_TRIPLO_DELUXE.toString())
      .setParameter("standard", QUARTO_STANDARD_TAIAMA.toString())
      .getSingleResult()));
  }

  private UUID gravaReserva(final BookingStatus status) {
    return this.gravaReserva(status, 1);
  }

  private UUID gravaReserva(final BookingStatus status, final int quantity) {
    return this.gravaReserva(status, quantity, QUARTO_TRIPLO_DELUXE);
  }

  private UUID gravaReserva(final BookingStatus status, final int quantity, final UUID quarto) {
    final var reserva = BookingEntity.builder()
      .id(UUID.randomUUID())
      .customerId(UUID.randomUUID())
      .reservationOrderId(UUID.randomUUID())
      .checkIn(LocalDate.of(2035, 3, 1))
      .checkOut(LocalDate.of(2035, 3, 5))
      .status(status)
      .totalPrice(new BigDecimal("456.0000"))
      .build();
    final var linha = BookingRoomEntity.builder()
      .id(UUID.randomUUID())
      .room(this.entityManager.getReference(RoomEntity.class, quarto))
      .booking(reserva)
      .price(new BigDecimal("456.0000"))
      .quantity(quantity)
      .build();
    reserva.setBookingRooms(Set.of(linha));
    this.entityManager.persist(reserva);
    return reserva.getId();
  }

  @Test
  @DisplayName("acha a reserva pelo identificador do pedido")
  void achaPeloIdentificadorDoPedido() {
    final var encontrada = this.repository.findByReservationOrderId(
      UUID.fromString("e2ee501c-9c7e-42ee-8896-a13b2e2713d1")
    );

    assertThat(encontrada)
      .isPresent()
      .hasValueSatisfying(reserva -> assertThat(reserva.getCheckIn()).isEqualTo(LocalDate.of(2023, 6, 15)));
  }

  @Test
  @DisplayName("devolve vazio para pedido inexistente")
  void devolveVazioParaPedidoInexistente() {
    final var encontrada = this.repository.findByReservationOrderId(UUID.randomUUID());

    assertThat(encontrada).isEmpty();
  }

}
