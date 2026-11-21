package com.hotel.booking.system.hotel.service.application.web.advice;

import com.hotel.booking.system.commons.application.dto.ErrorResponse;
import com.hotel.booking.system.commons.application.web.GlobalExceptionHandler;
import com.hotel.booking.system.hotel.service.core.domain.exception.HotelDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz para HTTP as falhas que a fronteira do serviço de hotéis sabe nomear. Estende
 * {@link GlobalExceptionHandler}, que cuida do que é comum aos serviços (validação, exceção do
 * framework e erro inesperado); aqui ficam só as exceções deste domínio.
 */
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
}
