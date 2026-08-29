package com.hotel.booking.system.customer.service.application.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JsonMapperConfiguration {

  // O bean é `JsonMapper` e não `ObjectMapper` porque no Jackson 3 o
  // `ObjectMapper` virou classe abstrata: quem é instanciável é o mapper de
  // cada formato, e o de JSON é este. É também o tipo que o
  // `JacksonJsonMessageConverter` do Spring AMQP recebe.
  @Bean
  public JsonMapper jsonMapper() {
    // Os três módulos que a versão anterior registrava à mão — JavaTimeModule,
    // ParameterNamesModule e Jdk8Module — foram absorvidos pelo jackson-databind
    // no Jackson 3 e não existem mais como artefato separado. Registrá-los
    // deixou de ser possível, e deixou de ser necessário.
    //
    // A configuração acontece no builder porque o mapper passou a ser imutável;
    // não há mais `configure(...)` depois de construído. A inclusão ALWAYS saiu
    // junto: era o default do Jackson, e a chamada anterior só o reafirmava.
    return JsonMapper.builder()
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();
  }

}
