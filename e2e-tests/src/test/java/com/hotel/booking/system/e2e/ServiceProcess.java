package com.hotel.booking.system.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Um serviço rodando como processo filho, a partir do jar executável que o
 * {@code package} deixou em {@code <modulo>/target}.
 *
 * <p>Processo filho, e não os quatro contextos Spring na mesma JVM, porque os quatro
 * {@code application.yml} têm o mesmo nome e vivem na raiz do classpath: numa JVM só, três
 * deles seriam ignorados em silêncio. É também o que mais se parece com a produção, onde
 * cada serviço é um processo com a própria configuração, passada por variável de ambiente.</p>
 *
 * <p>A saída padrão e a de erro vão para {@code target/e2e-logs/<nome>.log}. É o único rastro
 * de um serviço que não subiu ou que engoliu uma mensagem, e o teste o anexa à falha.</p>
 */
final class ServiceProcess {

  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  private final String name;

  private final Path jar;

  private final int port;

  private final String contextPath;

  private final Map<String, String> environment;

  private final Path log;

  private Process process;

  ServiceProcess(
    final String name,
    final String contextPath,
    final int port,
    final Map<String, String> environment,
    final Path logDirectory
  ) {
    this.name = name;
    this.jar = locateJar(name);
    this.port = port;
    this.contextPath = contextPath;
    this.environment = environment;
    this.log = logDirectory.resolve(name + ".log");
  }

  /**
   * Procura o jar executável do módulo. O {@code .original} que o repackage do Spring Boot
   * deixa ao lado é o jar sem as dependências e não sobe sozinho.
   */
  private static Path locateJar(final String module) {
    final var targetDirectory = Path.of("..", module, "target").toAbsolutePath().normalize();
    try (Stream<Path> files = Files.isDirectory(targetDirectory) ? Files.list(targetDirectory) : Stream.empty()) {
      return files
        .filter(file -> file.getFileName().toString().startsWith(module + "-"))
        .filter(file -> file.getFileName().toString().endsWith(".jar"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
          "Não achei o jar executável de %s em %s. Os testes ponta a ponta sobem os serviços pelo jar, então o `package` deles precisa ter rodado antes: use `mvn -B verify -Pe2e`, ou `mvn -B verify -Pe2e -pl e2e-tests -am`."
            .formatted(module, targetDirectory)
        ));
    }
    catch (final IOException e) {
      throw new IllegalStateException("Falha ao listar " + targetDirectory, e);
    }
  }

  void start() {
    try {
      Files.createDirectories(this.log.getParent());
      final var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
      final var command = List.of(
        java,
        "-Xmx256m",
        "-XX:TieredStopAtLevel=1",
        "-Djava.security.egd=file:/dev/./urandom",
        "-Duser.region=BR",
        "-Duser.language=pt",
        "-jar",
        this.jar.toString()
      );
      final var builder = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(this.log.toFile()));
      builder.environment().putAll(this.environment);
      this.process = builder.start();
    }
    catch (final IOException e) {
      throw new IllegalStateException("Falha ao iniciar " + this.name, e);
    }
  }

  /**
   * Espera o {@code /actuator/health} responder {@code UP}. Se o processo morrer antes disso,
   * falha na hora com o final do log, em vez de esperar o prazo inteiro por algo que não vem.
   */
  void awaitHealthy(final Duration timeout) {
    final var deadline = System.nanoTime() + timeout.toNanos();
    final var request = HttpRequest.newBuilder(URI.create(this.baseUrl() + "/actuator/health"))
      .timeout(Duration.ofSeconds(2))
      .GET()
      .build();
    while (System.nanoTime() < deadline) {
      if (!this.process.isAlive()) {
        throw new IllegalStateException("%s encerrou antes de ficar saudável (exit=%d):%n%s"
          .formatted(this.name, this.process.exitValue(), this.tail(60)));
      }
      try {
        final var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
          return;
        }
      }
      catch (final IOException ignored) {
        // Ainda subindo: a porta não aceita conexão até o servidor web iniciar.
      }
      catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrompido esperando " + this.name, e);
      }
      try {
        Thread.sleep(500);
      }
      catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrompido esperando " + this.name, e);
      }
    }
    throw new IllegalStateException("%s não ficou saudável em %s:%n%s".formatted(this.name, timeout, this.tail(60)));
  }

  void stop() {
    if (this.process == null) {
      return;
    }
    this.process.descendants().forEach(ProcessHandle::destroy);
    this.process.destroy();
    try {
      if (!this.process.waitFor(15, TimeUnit.SECONDS)) {
        this.process.destroyForcibly().waitFor();
      }
    }
    catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      this.process.destroyForcibly();
    }
    this.process = null;
  }

  /**
   * Encerra o processo sem aviso, como uma queda de máquina ou um {@code kill -9}: o serviço não
   * roda hook de desligamento, não confirma a mensagem em andamento e não fecha a transação. É o
   * oposto do {@link #stop()}, que pede o encerramento e espera.
   */
  void kill() {
    if (this.process == null) {
      return;
    }
    this.process.descendants().forEach(ProcessHandle::destroyForcibly);
    this.process.destroyForcibly();
    try {
      this.process.waitFor(15, TimeUnit.SECONDS);
    }
    catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    this.process = null;
  }

  boolean isRunning() {
    return this.process != null && this.process.isAlive();
  }

  String baseUrl() {
    return "http://localhost:" + this.port + this.contextPath;
  }

  String name() {
    return this.name;
  }

  /**
   * Últimas linhas do log do serviço, para anexar a uma falha de teste.
   */
  String tail(final int lines) {
    try {
      if (!Files.exists(this.log)) {
        return "(sem log)";
      }
      final var all = Files.readAllLines(this.log);
      return String.join(System.lineSeparator(), all.subList(Math.max(0, all.size() - lines), all.size()));
    }
    catch (final IOException e) {
      return "(log ilegível: " + e.getMessage() + ")";
    }
  }

}
