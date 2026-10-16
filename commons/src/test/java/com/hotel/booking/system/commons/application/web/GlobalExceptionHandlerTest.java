package com.hotel.booking.system.commons.application.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Tratamento de exceções comum aos serviços HTTP")
class GlobalExceptionHandlerTest {

  private static final String SECRET = "jdbc:postgresql://db.internal:5432/hotel?password=s3cr3t";

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    this.logs.start();
    this.handlerLogger.addAppender(this.logs);
    this.mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
      .setControllerAdvice(new TestAdvice())
      .build();
  }

  @AfterEach
  void tearDown() {
    this.handlerLogger.detachAppender(this.logs);
    this.logs.stop();
  }

  @Test
  @DisplayName("responde 500 com mensagem genérica e o envelope de erro quando a exceção é inesperada")
  void excecaoInesperadaResponde500ComMensagemGenerica() throws Exception {
    this.mockMvc.perform(get("/unexpected"))
      .andExpect(status().isInternalServerError())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("internal.server.error"))
      .andExpect(jsonPath("$.data").doesNotExist())
      .andExpect(jsonPath("$.time").isString());
  }

  @Test
  @DisplayName("não expõe a mensagem da exceção nem o stacktrace no corpo da resposta")
  void naoVazaDetalheDaExcecaoNoCorpo() throws Exception {
    final var body = this.mockMvc.perform(get("/unexpected"))
      .andReturn()
      .getResponse()
      .getContentAsString();

    assertThat(body)
      .doesNotContain("s3cr3t")
      .doesNotContain("jdbc")
      .doesNotContain("IllegalStateException")
      .doesNotContain("stackTrace")
      .doesNotContain("GlobalExceptionHandlerTest");
  }

  @Test
  @DisplayName("registra o stacktrace completo no log, e não só a mensagem")
  void registraOStacktraceCompletoNoLog() throws Exception {
    this.mockMvc.perform(get("/unexpected"));

    assertThat(this.logs.list)
      .filteredOn(event -> event.getLevel() == Level.ERROR)
      .singleElement()
      .satisfies(event -> {
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        assertThat(event.getThrowableProxy().getMessage()).isEqualTo(SECRET);
        assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
      });
  }

  @Test
  @DisplayName("responde 400 com a lista de campos inválidos quando a validação da entrada falha")
  void validacaoDeEntradaResponde400ComListaDeCampos() throws Exception {
    this.mockMvc.perform(post("/validation").contentType(MediaType.APPLICATION_JSON).content("{}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("request.invalid"))
      .andExpect(jsonPath("$.data.length()").value(2))
      .andExpect(jsonPath("$.data[0].field").value("name"))
      .andExpect(jsonPath("$.data[0].message").value("hotel.name.must.not.be.null-or-empty"))
      .andExpect(jsonPath("$.data[1].field").value("city"))
      .andExpect(jsonPath("$.data[1].message").value("hotel.locality.must.not.be.null"))
      .andExpect(jsonPath("$.time").isString());
  }

  @Test
  @DisplayName("responde 400 com o envelope de erro quando o corpo da requisição é ilegível")
  void corpoIlegivelResponde400ComEnvelope() throws Exception {
    this.mockMvc.perform(post("/payload").contentType(MediaType.APPLICATION_JSON).content("{"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("request.invalid"))
      .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  @DisplayName("responde 405 com o envelope de erro e preserva o cabeçalho Allow")
  void metodoNaoSuportadoResponde405ComEnvelope() throws Exception {
    this.mockMvc.perform(get("/payload"))
      .andExpect(status().isMethodNotAllowed())
      .andExpect(header().string("Allow", "POST"))
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("request.invalid"));
  }

  @Test
  @DisplayName("mantém 404 para o recurso que o framework não encontra, em vez de promovê-lo a 500")
  void recursoInexistenteDoFrameworkContinua404() throws Exception {
    this.mockMvc.perform(get("/missing-resource"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("request.invalid"));
  }

  @Test
  @DisplayName("não registra como erro a rejeição do framework causada pelo cliente")
  void rejeicaoDoClienteNaoVaiParaOLogComoErro() throws Exception {
    this.mockMvc.perform(post("/payload").contentType(MediaType.APPLICATION_JSON).content("{"));

    assertThat(this.logs.list).noneMatch(event -> event.getLevel() == Level.ERROR);
  }

  @RestControllerAdvice
  static class TestAdvice extends GlobalExceptionHandler {
  }

  record Payload(String name) {
  }

  @RestController
  static class ThrowingController {

    @GetMapping("/unexpected")
    String unexpected() {
      throw new IllegalStateException(SECRET);
    }

    @PostMapping("/payload")
    String payload(@RequestBody final Payload payload) {
      return payload.name();
    }

    @GetMapping("/missing-resource")
    String missing() throws NoResourceFoundException {
      throw new NoResourceFoundException(HttpMethod.GET, "/actuator/env", "/actuator/env");
    }

    @PostMapping("/validation")
    String validation() throws Exception {
      final var method = ThrowingController.class.getDeclaredMethod("payload", Payload.class);
      final var bindingResult = new BeanPropertyBindingResult(new Payload(null), "payload");
      bindingResult.addError(new FieldError("payload", "name", "hotel.name.must.not.be.null-or-empty"));
      bindingResult.addError(new FieldError("payload", "city", "hotel.locality.must.not.be.null"));
      throw new MethodArgumentNotValidException(new MethodParameter(method, 0), bindingResult);
    }
  }
}
