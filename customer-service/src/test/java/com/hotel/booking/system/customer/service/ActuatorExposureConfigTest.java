package com.hotel.booking.system.customer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guarda de regressão da exposição do Actuator, lida direto do {@code application.yml} do
 * serviço (issue #12).
 *
 * <p>O comportamento visto de fora — {@code /actuator/env} em 404, {@code /actuator/health}
 * sem detalhes — é provado pelo {@code ActuatorExposureIT} do módulo {@code e2e-tests}, mas
 * aquele teste só roda com o label {@code e2e} no pull request. Este roda em todo
 * {@code mvn verify}: reintroduzir {@code include: "*"} quebra o build comum, e liberar outro
 * endpoint passa a exigir mexer aqui — uma decisão deliberada, junto com a coleta que a
 * justifique, e não um efeito colateral.</p>
 *
 * <p>Usa o mesmo {@code Binder} que o Spring Boot usa na subida, então a lista separada por
 * vírgula do YAML é interpretada como o Actuator a interpretaria.</p>
 */
@DisplayName("Exposição do Actuator no customer-service")
class ActuatorExposureConfigTest {

  private static final String INCLUDE = "management.endpoints.web.exposure.include";

  private static final String SHOW_DETAILS = "management.endpoint.health.show-details";

  private final Binder binder = loadBinder();

  private static Binder loadBinder() {
    try {
      final var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
      return new Binder(ConfigurationPropertySources.from(sources));
    }
    catch (final IOException e) {
      throw new UncheckedIOException("Falha ao ler o application.yml do serviço", e);
    }
  }

  private <T> Optional<T> bind(final String name, final Bindable<T> target) {
    final var result = this.binder.bind(name, target);
    return result.isBound() ? Optional.of(result.get()) : Optional.empty();
  }

  @Test
  @DisplayName("expõe pela web somente health e info")
  void expoeSomenteHealthEInfo() {
    final var exposed = this.bind(INCLUDE, Bindable.listOf(String.class));

    assertThat(exposed)
      .as("`%s` deve listar só health e info; `*` expõe env, configprops e threaddump (issue #12)", INCLUDE)
      .hasValueSatisfying(endpoints -> assertThat(endpoints).containsExactlyInAnyOrder("health", "info"));
  }

  @Test
  @DisplayName("oculta os detalhes do health de forma explícita")
  void ocultaDetalhesDoHealthDeFormaExplicita() {
    assertThat(this.bind(SHOW_DETAILS, Bindable.of(String.class)))
      .as("`%s` deve ser `never` explícito, para o default não depender da versão do Spring Boot", SHOW_DETAILS)
      .hasValue("never");
  }

}
