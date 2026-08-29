package com.hotel.booking.system.booking.service.data.db;

import com.hotel.booking.system.booking.service.data.db.repository.BookingJpaRepository;
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
 * decide se um quarto está livre no período pedido. Como usa {@code between} sobre
 * {@code date}, ela depende de como o banco compara datas — e é exatamente o tipo de coisa
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
  @DisplayName("trata as bordas do período como ocupadas")
  void trataBordasComoOcupadas() {
    final var noCheckIn = this.repository.findByRoomIdAndPeriod(
      QUARTO_TRIPLO_DELUXE,
      LocalDate.of(2023, 6, 15),
      LocalDate.of(2023, 6, 15)
    );
    final var noCheckOut = this.repository.findByRoomIdAndPeriod(
      QUARTO_TRIPLO_DELUXE,
      LocalDate.of(2023, 6, 25),
      LocalDate.of(2023, 6, 25)
    );

    assertThat(noCheckIn).as("o `between` do JPQL é inclusivo nas duas pontas").hasSize(1);
    assertThat(noCheckOut).hasSize(1);
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
   * Trava uma lacuna conhecida da consulta, e não um comportamento desejado.
   *
   * <p>A cláusula só testa se o check-in <em>ou</em> o check-out pedido caem dentro de uma
   * reserva existente. Um período que <strong>contém</strong> a reserva inteira — chega antes
   * e sai depois — não casa com nenhuma das duas, e o quarto aparece como livre num intervalo
   * em que está ocupado.</p>
   *
   * <p>A asserção é a de hoje de propósito: a migração de banco não pode alterar esta
   * semântica sem que alguém perceba. Corrigi-la é trabalho de outra issue, com um teste que
   * inverte esta expectativa.</p>
   */
  @Test
  @DisplayName("não acha a reserva quando o período pedido a engloba (lacuna conhecida)")
  void naoAchaQuandoPeriodoEngloba() {
    final var encontradas = this.repository.findByRoomIdAndPeriod(
      QUARTO_TRIPLO_DELUXE,
      LocalDate.of(2023, 6, 1),
      LocalDate.of(2023, 7, 31)
    );

    assertThat(encontradas)
      .as("lacuna do `between`: o período engloba a reserva e mesmo assim o quarto passa por livre")
      .isEmpty();
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
