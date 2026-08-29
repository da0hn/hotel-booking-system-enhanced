package com.hotel.booking.system.commons.core.domain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Trava a propriedade da qual a saga inteira depende: toda classe de evento
 * precisa ser construível pelo Jackson a partir de um objeto JSON.
 * <p>
 * Este teste existe porque a atualização para o Jackson 3 quebrou exatamente
 * isso, e nada acusou: os serviços subiam saudáveis, o `POST /hotel/booking`
 * respondia 200, e a saga parava na primeira resposta. Um teste de domínio não
 * alcança esse tipo de falha, porque ela não está no domínio — está na ponte
 * entre ele e a fila.
 * <p>
 * As classes são descobertas varrendo o pacote, e não listadas à mão, para que
 * um evento novo entre na cobertura sem ninguém precisar lembrar disso.
 */
@DisplayName("Contrato de desserialização dos eventos")
class ContratoDeEventosTest {

  // Deliberadamente sem configuração: se um evento só desserializa com um
  // mapper ajustado, ele depende de uma combinação que os quatro serviços
  // precisariam repetir — e que um deles vai esquecer.
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

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
        .map(ContratoDeEventosTest::carregar)
        // Filtrar por `Event.class::isAssignableFrom` seria o reflexo natural e
        // deixaria justamente as classes erradas de fora: `BookingRoomStatusUpdatedEvent`
        // e `PaymentRequestedEvent` não implementam `Event`, e é sob a primeira que
        // vivem as duas subclasses que quebraram na migração. O critério aqui é o
        // pacote, e a única exceção é nomeada.
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
