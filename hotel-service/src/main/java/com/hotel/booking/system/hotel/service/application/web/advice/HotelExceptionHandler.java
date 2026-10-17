package com.hotel.booking.system.hotel.service.application.web.advice;

import com.hotel.booking.system.commons.application.dto.ErrorResponse;
import com.hotel.booking.system.commons.application.dto.impl.ApiErrorResponse;
import com.hotel.booking.system.commons.application.web.GlobalExceptionHandler;
import com.hotel.booking.system.commons.core.application.exception.MessagePublishException;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import com.hotel.booking.system.hotel.service.core.domain.exception.HotelDomainException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz para HTTP as falhas que a fronteira do serviço de hotéis sabe nomear. Estende
 * {@link GlobalExceptionHandler}, que cuida do que é comum aos serviços (validação, exceção do
 * framework e erro inesperado); aqui ficam só as exceções deste domínio.
 */
@Slf4j
@RestControllerAdvice
public class HotelExceptionHandler extends GlobalExceptionHandler {

  /**
   * Responde 422: a requisição é bem formada, mas viola uma regra do domínio (hóspedes acima da
   * capacidade, categoria inexistente). O 422 vale também para o quarto que o corpo cita e não
   * existe, e não o 404: este serviço nunca endereça um quarto pela URL, e o 404 diz que o recurso
   * da URL não existe.
   */
  @ExceptionHandler(HotelDomainException.class)
  public ResponseEntity<ErrorResponse<Void>> handleDomain(final HotelDomainException exception) {
    return this.reject(HttpStatus.UNPROCESSABLE_CONTENT, exception);
  }

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
