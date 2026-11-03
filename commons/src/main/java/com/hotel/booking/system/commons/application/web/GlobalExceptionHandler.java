package com.hotel.booking.system.commons.application.web;

import com.hotel.booking.system.commons.application.dto.ErrorResponse;
import com.hotel.booking.system.commons.application.dto.impl.ApiErrorResponse;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Optional;

/**
 * Base dos advices HTTP dos serviços: toda falha de uma requisição sai no envelope
 * {@link ApiErrorResponse}, o mesmo formato ({@code success}, {@code message}, {@code data},
 * {@code time}) das respostas de sucesso. Cada serviço registra um {@code @RestControllerAdvice}
 * próprio que estende esta classe e acrescenta o tratamento das exceções do seu domínio.
 *
 * <p>Estende {@link ResponseEntityExceptionHandler} e não declara só um
 * {@code @ExceptionHandler(Exception.class)}, de propósito: um catch-all solto também captura as
 * exceções do próprio Spring MVC e as promove a {@code 500}. O caso que importa aqui é o
 * {@code NoResourceFoundException}, o {@code 404} que {@code /actuator/env} devolve por não estar
 * exposto. Com a base, cada exceção do framework mantém o status que o Spring lhe dá, e o
 * {@link #handleUnexpected} só recebe o que sobra.</p>
 *
 * <p>A {@code message} é sempre uma chave estável, nunca o texto da exceção: as de domínio já nascem
 * de {@link ApplicationMessage}, e as do framework e as inesperadas são trocadas por
 * {@link ApplicationMessage#REQUEST_INVALID} e {@link ApplicationMessage#INTERNAL_SERVER_ERROR}.
 * O detalhe fica no log.</p>
 *
 * <p>Falha do servidor ({@code 5xx}) é registrada em {@code ERROR} com o stacktrace. Rejeição do
 * cliente ({@code 4xx}) é registrada em {@code WARN} só com a mensagem: uma consulta que chega antes
 * de o customer-service processar o evento da reserva responde {@code 404} e se repete até o evento
 * chegar, e um stacktrace por tentativa afogaria o log.</p>
 */
@Slf4j
public abstract class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  /**
   * Responde {@code 500} com uma mensagem genérica para qualquer exceção que nenhum outro handler
   * reconheceu. O corpo não traz a mensagem nem o stacktrace: eles podem conter URL de banco,
   * nome de fila ou dado do usuário.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse<Void>> handleUnexpected(final Exception exception) {
    log.error("Unexpected error while handling the request", exception);
    return ResponseEntity
      .status(HttpStatus.INTERNAL_SERVER_ERROR)
      .body(ApiErrorResponse.of(ApplicationMessage.INTERNAL_SERVER_ERROR));
  }

  /**
   * Resposta de rejeição de uma exceção de domínio: o corpo leva a mensagem da exceção, que é uma
   * chave de {@link ApplicationMessage}. Quem estende escolhe o status.
   */
  protected final ResponseEntity<ErrorResponse<Void>> reject(
    final HttpStatus status,
    final Exception exception
  ) {
    log.warn("Request rejected with status {}: {}", status.value(), exception.getMessage());
    return ResponseEntity
      .status(status)
      .body(ApiErrorResponse.of(exception.getMessage()));
  }

  /**
   * Faz um {@code page} ou {@code size} presente e vazio ser recusado pela validação, em vez de valer
   * como ausente: o Spring converte {@code ?page=} em {@code null}, e a validação não avalia
   * {@code null}. É a outra metade do {@link #handleHandlerMethodValidationException}, que lista a
   * violação que este binder provoca (ver {@link EmptyIsInvalidIntegerEditor}).
   *
   * <p>Como um {@code @InitBinder} de {@code @ControllerAdvice}, vale para todos os controllers de
   * quem estende esta base, sem registro nem repetição por serviço. Casa por <strong>nome</strong>
   * ({@code page} e {@code size}) e não por tipo, de propósito: valer para todo {@code Integer}
   * faria um parâmetro futuro sem restrição receber {@code Integer.MIN_VALUE} no lugar de
   * {@code null}. Em contrapartida, um parâmetro com um desses nomes precisa declarar uma restrição,
   * ou o vazio passará por um valor legítimo.</p>
   */
  @InitBinder({"page", "size"})
  protected void rejectEmptyPageParameters(final WebDataBinder binder) {
    binder.registerCustomEditor(Integer.class, new EmptyIsInvalidIntegerEditor());
  }

  /**
   * Lista os campos recusados pela validação no {@code data} do {@code 400}.
   */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
    final MethodArgumentNotValidException exception,
    final HttpHeaders headers,
    final HttpStatusCode status,
    final WebRequest request
  ) {
    final var violations = exception.getBindingResult().getFieldErrors().stream()
      .map(error -> new ApiErrorResponse.FieldViolation(error.getField(), error.getDefaultMessage()))
      .toList();
    return this.handleExceptionInternal(
      exception,
      ApiErrorResponse.of(violations, ApplicationMessage.REQUEST_INVALID),
      headers,
      status,
      request
    );
  }

  /**
   * Lista os parâmetros recusados pela validação no {@code data} do {@code 400}, no mesmo formato
   * do {@link #handleMethodArgumentNotValid}. É o caso de uma restrição posta direto num parâmetro
   * do controller, como o {@code @Min} de um {@code @RequestParam}: o Spring a avalia sozinho e
   * lança esta exceção, e não a de campo de corpo.
   *
   * <p>O {@code field} é o nome do parâmetro Java, que coincide com o da requisição enquanto o
   * {@code @RequestParam} não o renomeia; o {@code message} é a chave declarada na restrição.</p>
   */
  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
    final HandlerMethodValidationException exception,
    final HttpHeaders headers,
    final HttpStatusCode status,
    final WebRequest request
  ) {
    final var violations = exception.getParameterValidationResults().stream()
      .flatMap(result -> result.getResolvableErrors().stream()
        .map(error -> new ApiErrorResponse.FieldViolation(
          Optional.ofNullable(result.getMethodParameter().getParameterName()).orElse(""),
          error.getDefaultMessage()
        )))
      .toList();
    return this.handleExceptionInternal(
      exception,
      ApiErrorResponse.of(violations, ApplicationMessage.REQUEST_INVALID),
      headers,
      status,
      request
    );
  }

  /**
   * Ponto único por onde passam as exceções do Spring MVC: troca o {@code ProblemDetail} padrão pelo
   * envelope, preservando o status e os cabeçalhos (por exemplo o {@code Allow} do {@code 405}).
   *
   * <p>O {@code body} que chega é {@code null} (o Spring monta o {@code ProblemDetail} depois) ou já um
   * {@code ProblemDetail} (o {@code 400} de corpo ilegível o monta antes de chamar). Os dois são
   * descartados; qualquer outro valor foi posto por esta classe, como a lista de campos inválidos, e
   * segue intacto.</p>
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
    final Exception exception,
    final Object body,
    final HttpHeaders headers,
    final HttpStatusCode statusCode,
    final WebRequest request
  ) {
    if (statusCode.is5xxServerError()) {
      log.error("Request failed with status {}", statusCode.value(), exception);
    } else {
      log.warn("Request rejected with status {}: {}", statusCode.value(), exception.getMessage());
    }
    final var envelope = body == null || body instanceof ProblemDetail ? ApiErrorResponse.of(
      statusCode.is5xxServerError() ? ApplicationMessage.INTERNAL_SERVER_ERROR : ApplicationMessage.REQUEST_INVALID
    ) : body;
    return super.handleExceptionInternal(exception, envelope, headers, statusCode, request);
  }
}
