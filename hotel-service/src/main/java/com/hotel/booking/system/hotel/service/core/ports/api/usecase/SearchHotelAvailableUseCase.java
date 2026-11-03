package com.hotel.booking.system.hotel.service.core.ports.api.usecase;

import com.hotel.booking.system.commons.core.domain.pagination.Page;
import com.hotel.booking.system.commons.core.domain.pagination.PageRequest;
import com.hotel.booking.system.hotel.service.core.application.dto.SearchHotelAvailableInput;
import com.hotel.booking.system.hotel.service.core.application.dto.SearchHotelAvailableOutput;

import java.util.List;

public interface SearchHotelAvailableUseCase {

  /**
   * Todos os hotéis que atendem aos filtros, sem ordem nem limite.
   */
  List<SearchHotelAvailableOutput> execute(SearchHotelAvailableInput input);

  /**
   * Uma página dos hotéis que atendem aos filtros, por {@code name} e, no empate, por {@code id}.
   * A página além do fim é vazia, não é falha.
   */
  Page<SearchHotelAvailableOutput> execute(SearchHotelAvailableInput input, PageRequest pageRequest);

}
