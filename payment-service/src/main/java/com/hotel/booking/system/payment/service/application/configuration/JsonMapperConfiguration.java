package com.hotel.booking.system.payment.service.application.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JsonMapperConfiguration {

  /**
   * O bean é {@code JsonMapper} e não {@code ObjectMapper} porque no Jackson 3 o
   * {@code ObjectMapper} virou classe abstrata: quem é instanciável é o mapper de cada
   * formato, e o de JSON é este. É também o tipo que o
   * {@code JacksonJsonMessageConverter} do Spring AMQP recebe.
   * <p>
   * Os três módulos que a versão anterior registrava à mão — {@code JavaTimeModule},
   * {@code ParameterNamesModule} e {@code Jdk8Module} — foram absorvidos pelo
   * jackson-databind e não existem mais como artefato separado: registrá-los deixou de
   * ser possível, e deixou de ser necessário.
   * <p>
   * A configuração acontece no builder porque o mapper passou a ser imutável, e não há
   * mais {@code configure(...)} depois de construído. A inclusão {@code ALWAYS} saiu
   * junto: era o default do Jackson, e a chamada anterior apenas o reafirmava.
   */
  @Bean
  public JsonMapper jsonMapper() {
    return JsonMapper.builder()
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();
  }

}
