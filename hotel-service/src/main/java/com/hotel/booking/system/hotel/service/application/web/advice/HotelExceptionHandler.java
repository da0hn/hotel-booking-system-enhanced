package com.hotel.booking.system.hotel.service.application.web.advice;

import com.hotel.booking.system.commons.application.dto.ErrorResponse;
import com.hotel.booking.system.commons.application.dto.impl.ApiErrorResponse;
import com.hotel.booking.system.commons.core.application.exception.MessagePublishException;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz para HTTP as falhas que a fronteira do serviço de hotéis já sabe nomear.
 *
 * <p>Por ora só a falha de publicação: sem ela, a exceção do publisher viraria um 500 genérico
 * e o cliente não teria como distinguir "o broker está fora, tente de novo" de um defeito do
 * serviço. O tratamento das demais exceções (domínio, validação) é da issue #9.</p>
 */
@Slf4j
@RestControllerAdvice
public class HotelExceptionHandler {

  /**
   * Responde 503 com uma chave estável e sem detalhe de infraestrutura: o exchange e o motivo
   * da recusa ficam no log, não na resposta.
   */
  @ExceptionHandler(MessagePublishException.class)
  public ResponseEntity<ErrorResponse<Void>> handleMessagePublish(final MessagePublishException exception) {
    log.error("Could not publish the event while handling the request", exception);
    return ResponseEntity
      .status(HttpStatus.SERVICE_UNAVAILABLE)
      .body(ApiErrorResponse.of(ApplicationMessage.HOTEL_BOOKING_TEMPORARILY_UNAVAILABLE));
  }
}
