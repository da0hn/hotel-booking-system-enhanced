package com.hotel.booking.system.booking.service.infrastructure.db;

import com.hotel.booking.system.booking.service.infrastructure.db.repository.BookingJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercita as duas consultas escritas à mão do {@code booking-service}.
 *
 * <p>A {@code findByRoomIdAndPeriod} é o coração da regra de disponibilidade: é ela que
 * decide se um quarto está livre no período pedido. Como compara colunas {@code date}
 * com desigualdades estritas, ela depende de como o banco compara datas — e é exatamente o tipo de coisa
 * que uma troca de engine muda em silêncio.</p>
 */
@DisplayName("Consultas de reserva por período")
class BookingJpaRepositoryIT extends AbstractDatabaseIT {

  /** Quarto Triplo Deluxe do Amazon Plaza; o seed o reserva de 15 a 25 de junho de 2023. */
  private static final UUID QUARTO_TRIPLO_DELUXE = UUID.fromString("2223dc04-831a-4bac-aef5-e22195575cc6");

  /** Quarto Standard do Taiamã; o seed o reserva duas vezes, em julho de 2023. */
  private static final UUID QUARTO_STANDARD_TAIAMA = UUID.fromString("2c5c52bb-739d-468b-a2a2-b8b917a2004f");

  @Autowired
  private BookingJpaRepository repository;

  @Test
  @DisplayName("acha a reserva quando o check-in cai dentro do período ocupado")
  void achaQuandoCheckInCaiDentro() {
    final var encontradas = this.repository.findByRoomIdAndPeriod(
      QUARTO_TRIPLO_DELUXE,
      LocalDate.of(2023, 6, 20),
      LocalDate.of(2023, 6, 30)
    );

    assertThat(encontradas).hasSize(1);
  }

  @Test
  @DisplayName("acha a reserva quando o check-out cai dentro do período ocupado")
  void achaQuandoCheckOutCaiDentro() {
    final var encontradas = this.repository.findByRoomIdAndPeriod(
      QUARTO_TRIPLO_DELUXE,
      LocalDate.of(2023, 6, 10),
      LocalDate.of(2023, 6, 20)
    );

    assertThat(encontradas).hasSize(1);
  }

  @Test
  @DisplayName("acha a reserva quando o período pedido cai inteiro dentro dela")
  void achaQuandoPeriodoCaiInteiroDentro() {
    final var encontradas = this.repository.findByRoomIdAndPeriod(
      QUARTO_TRIPLO_DELUXE,
      LocalDate.of(2023, 6, 18),
      LocalDate.of(2023, 6, 20)
    );

    assertThat(encontradas).hasSize(1);
  }

  @Test
  @DisplayName("não trata o dia de troca como conflito: o check-out de uma reserva libera o check-in da outra")
  void naoTrataODiaDeTrocaComoConflito() {
    final var comecaNoCheckOut = this.repository.findByRoomIdAndPeriod(
      QUARTO_TRIPLO_DELUXE,
      LocalDate.of(2023, 6, 25),
      LocalDate.of(2023, 6, 27)
    );
    final var terminaNoCheckIn = this.repository.findByRoomIdAndPeriod(
      QUARTO_TRIPLO_DELUXE,
      LocalDate.of(2023, 6, 10),
      LocalDate.of(2023, 6, 15)
    );

    assertThat(comecaNoCheckOut).as("entra no dia em que a reserva sai").isEmpty();
    assertThat(terminaNoCheckIn).as("sai no dia em que a reserva entra").isEmpty();
  }

  @Test
  @DisplayName("devolve vazio quando o período não encosta em nenhuma reserva")
  void devolveVazioQuandoPeriodoNaoEncosta() {
    final var encontradas = this.repository.findByRoomIdAndPeriod(
      QUARTO_TRIPLO_DELUXE,
      LocalDate.of(2023, 8, 1),
      LocalDate.of(2023, 8, 10)
    );

    assertThat(encontradas).isEmpty();
  }

  @Test
  @DisplayName("devolve as duas reservas do quarto ocupado em julho")
  void devolveAsDuasReservasDeJulho() {
    final var encontradas = this.repository.findByRoomIdAndPeriod(
      QUARTO_STANDARD_TAIAMA,
      LocalDate.of(2023, 7, 10),
      LocalDate.of(2023, 7, 15)
    );

    assertThat(encontradas).hasSize(2);
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
    final var encontradas = this.repository.findByRoomIdAndPeriod(
      QUARTO_TRIPLO_DELUXE,
      LocalDate.of(2023, 6, 1),
      LocalDate.of(2023, 7, 31)
    );

    assertThat(encontradas)
      .as("o período engloba a reserva: o quarto está ocupado nesse intervalo")
      .hasSize(1);
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
