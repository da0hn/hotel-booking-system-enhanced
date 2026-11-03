package com.hotel.booking.system.booking.service.infrastructure.db;

import com.hotel.booking.system.commons.core.application.idempotency.ProcessedEventRepository;
import com.hotel.booking.system.commons.infrastructure.messaging.idempotency.JdbcIdempotencyConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova, contra o PostgreSQL real e com o usuário restrito do serviço, o registro de eventos
 * processados que o {@code commons} oferece. A tabela é a da migration {@code V008} do próprio
 * serviço, resolvida pelo {@code currentSchema} da URL: a classe do {@code commons} não sabe em
 * que schema está.
 */
@Import(JdbcIdempotencyConfiguration.class)
@DisplayName("Registro de eventos processados (booking-service)")
class ProcessedEventRepositoryIT extends AbstractDatabaseIT {

  private static final String TIPO = "com.hotel.booking.system.commons.core.domain.event.BookingRoomRequestedEvent";

  @Autowired
  private ProcessedEventRepository repository;

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private JdbcClient jdbc;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @AfterEach
  void limpaOQueOsTestesSemTransacaoConfirmaram() {
    this.jdbc.sql("delete from processed_event where event_type = 'teste.concorrencia'").update();
  }

  @Test
  @DisplayName("aceita o evento na primeira vez e recusa a reentrega")
  void aceitaNaPrimeiraVezERecusaAReentrega() {
    final var evento = UUID.randomUUID().toString();

    assertThat(this.repository.register(evento, TIPO)).isTrue();
    assertThat(this.repository.register(evento, TIPO)).isFalse();
    assertThat(this.contagem(evento)).isEqualTo(1);
  }

  /**
   * É a razão de o registro ser um {@code INSERT ... ON CONFLICT DO NOTHING}: num
   * {@code save()} com {@code catch} da violação de unicidade, o PostgreSQL deixa a transação
   * abortada e o efeito do evento, que roda logo depois, falharia com
   * <i>current transaction is aborted</i>.
   */
  @Test
  @DisplayName("mantém a transação utilizável depois de recusar uma duplicata")
  void duplicataNaoAbortaATransacao() {
    final var evento = UUID.randomUUID().toString();
    this.repository.register(evento, TIPO);

    this.repository.register(evento, TIPO);

    assertThat(this.contagem(evento)).as("a transação seguiu válida").isEqualTo(1);
  }

  @Test
  @DisplayName("expurga só os registros anteriores ao limite")
  void expurgaSoOsRegistrosAnterioresAoLimite() {
    final var velho = UUID.randomUUID().toString();
    final var recente = UUID.randomUUID().toString();
    this.inserirProcessadoEm(velho, "2026-01-01T00:00:00Z");
    this.inserirProcessadoEm(recente, "2026-06-01T00:00:00Z");

    final var removidos = this.repository.deleteRegisteredBefore(Instant.parse("2026-03-01T00:00:00Z"));

    assertThat(removidos).isEqualTo(1);
    assertThat(this.contagem(velho)).isZero();
    assertThat(this.contagem(recente)).isEqualTo(1);
  }

  /**
   * Duas entregas do mesmo evento em transações simultâneas: a chave primária faz a segunda
   * esperar a primeira. Se a primeira confirma, a segunda não insere e descarta; é o que impede
   * o efeito de rodar duas vezes mesmo sem nenhum lock de aplicação.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("faz a segunda de duas entregas simultâneas esperar e descartar quando a primeira confirma")
  void entregasSimultaneasProcessamUmaSoVezQuandoAPrimeiraConfirma() throws Exception {
    final var evento = UUID.randomUUID().toString();
    final var primeiraRegistrou = new CountDownLatch(1);
    final var primeiraPodeConfirmar = new CountDownLatch(1);
    final var tx = new TransactionTemplate(this.transactionManager);
    try (final var executor = Executors.newFixedThreadPool(2)) {
      final var primeira = executor.submit(() -> tx.execute(status -> {
        final var novo = this.repository.register(evento, "teste.concorrencia");
        primeiraRegistrou.countDown();
        aguarda(primeiraPodeConfirmar);
        return novo;
      }));
      assertThat(primeiraRegistrou.await(30, TimeUnit.SECONDS)).isTrue();
      final var segunda = executor.submit(() -> tx.execute(status -> this.repository.register(evento, "teste.concorrencia")));
      Thread.sleep(500);
      assertThat(segunda.isDone()).as("a segunda espera a primeira terminar").isFalse();

      primeiraPodeConfirmar.countDown();

      assertThat(primeira.get(30, TimeUnit.SECONDS)).isTrue();
      assertThat(segunda.get(30, TimeUnit.SECONDS)).isFalse();
    }
    assertThat(this.contagem(evento)).isEqualTo(1);
  }

  /**
   * O outro lado: se a primeira falha e desfaz, a segunda passa a ser a que processa. Sem isso a
   * falha de uma tentativa descartaria o evento para sempre.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("deixa a segunda entrega processar quando a primeira desfaz a transação")
  void segundaEntregaProcessaQuandoAPrimeiraDesfaz() throws Exception {
    final var evento = UUID.randomUUID().toString();
    final var primeiraRegistrou = new CountDownLatch(1);
    final var primeiraPodeDesfazer = new CountDownLatch(1);
    final var tx = new TransactionTemplate(this.transactionManager);
    try (final var executor = Executors.newFixedThreadPool(2)) {
      final var primeira = executor.submit(() -> tx.execute(status -> {
        this.repository.register(evento, "teste.concorrencia");
        primeiraRegistrou.countDown();
        aguarda(primeiraPodeDesfazer);
        status.setRollbackOnly();
        return null;
      }));
      assertThat(primeiraRegistrou.await(30, TimeUnit.SECONDS)).isTrue();
      final var segunda = executor.submit(() -> tx.execute(status -> this.repository.register(evento, "teste.concorrencia")));

      primeiraPodeDesfazer.countDown();

      primeira.get(30, TimeUnit.SECONDS);
      assertThat(segunda.get(30, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(this.contagem(evento)).isEqualTo(1);
  }

  private void inserirProcessadoEm(final String evento, final String instante) {
    this.entityManager
      .createNativeQuery("insert into processed_event (event_id, event_type, processed_at) values (:id, :tipo, cast(:em as timestamptz))")
      .setParameter("id", evento)
      .setParameter("tipo", TIPO)
      .setParameter("em", instante)
      .executeUpdate();
    this.entityManager.flush();
  }

  private long contagem(final String evento) {
    return this.jdbc
      .sql("select count(*) from processed_event where event_id = :id")
      .param("id", evento)
      .query(Long.class)
      .single();
  }

  private static void aguarda(final CountDownLatch trava) {
    try {
      trava.await(30, TimeUnit.SECONDS);
    }
    catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

}
