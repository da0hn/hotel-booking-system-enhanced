package com.hotel.booking.system.customer.service.application.web.advice;

import com.hotel.booking.system.commons.application.dto.ErrorResponse;
import com.hotel.booking.system.commons.application.web.GlobalExceptionHandler;
import com.hotel.booking.system.customer.service.core.domain.exception.CustomerDomainException;
import com.hotel.booking.system.customer.service.core.domain.exception.CustomerNotFoundException;
import com.hotel.booking.system.customer.service.core.domain.exception.ReservationOrderNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz para HTTP as exceções do domínio de clientes. Estende {@link GlobalExceptionHandler}, que
 * cuida do que é comum aos serviços; aqui ficam só as exceções deste domínio.
 */
@RestControllerAdvice
public class CustomerExceptionHandler extends GlobalExceptionHandler {

  /**
   * Responde 404: o cliente ou a ordem de reserva endereçados pela URL não existem. Vale lembrar que a
   * ordem só existe depois de o serviço processar o evento da reserva, então quem consulta logo após
   * pedir a reserva pode receber 404 e tentar de novo.
   */
  @ExceptionHandler({CustomerNotFoundException.class, ReservationOrderNotFoundException.class})
  public ResponseEntity<ErrorResponse<Void>> handleNotFound(final CustomerDomainException exception) {
    return this.reject(HttpStatus.NOT_FOUND, exception);
  }

  /**
   * Responde 422 para as demais violações de regra do domínio. As duas subclasses de "não encontrado"
   * têm handler próprio acima e, por serem mais específicas, nunca chegam aqui.
   */
  @ExceptionHandler(CustomerDomainException.class)
  public ResponseEntity<ErrorResponse<Void>> handleDomain(final CustomerDomainException exception) {
    return this.reject(HttpStatus.UNPROCESSABLE_CONTENT, exception);
  }
}
