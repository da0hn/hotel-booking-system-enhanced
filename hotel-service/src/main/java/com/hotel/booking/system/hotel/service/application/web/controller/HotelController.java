package com.hotel.booking.system.hotel.service.application.web.controller;

import com.hotel.booking.system.commons.application.dto.CollectionResponse;
import com.hotel.booking.system.commons.application.dto.Response;
import com.hotel.booking.system.commons.application.dto.impl.ResponseEntityAdapter;
import com.hotel.booking.system.commons.core.domain.pagination.PageRequest;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import com.hotel.booking.system.hotel.service.application.service.HotelApplicationService;
import com.hotel.booking.system.hotel.service.core.application.dto.BookingRoomInput;
import com.hotel.booking.system.hotel.service.core.application.dto.BookingRoomOutput;
import com.hotel.booking.system.hotel.service.core.application.dto.RegisterHotelInput;
import com.hotel.booking.system.hotel.service.core.application.dto.RegisterHotelOutput;
import com.hotel.booking.system.hotel.service.core.application.dto.SearchHotelAvailableInput;
import com.hotel.booking.system.hotel.service.core.application.dto.SearchHotelAvailableOutput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@AllArgsConstructor
@RequestMapping("/hotel")
public class HotelController {

  private static final int DEFAULT_PAGE_NUMBER = 0;
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MIN_PAGE_SIZE = 1;
  private static final int MAX_PAGE_SIZE = 100;

  private final HotelApplicationService hotelApplicationService;

  @PostMapping
  public ResponseEntity<Response<RegisterHotelOutput>> register(
    @Valid @RequestBody final RegisterHotelInput input
  ) {
    final var output = this.hotelApplicationService.register(input);
    return ResponseEntityAdapter.of(output);
  }

  /**
   * Busca hotéis pelos filtros. O formato da resposta depende de a requisição pedir página:
   * sem {@code page} nem {@code size}, devolve a lista inteira, como sempre; com qualquer um dos
   * dois, devolve uma página sem contagem total e o outro parâmetro assume o padrão.
   *
   * <p>{@code page} e {@code size} são {@code Integer} e não {@code Optional<Integer>} porque o
   * Spring só aplica a restrição sozinho quando a anotação está no próprio parâmetro; num
   * {@code Optional<@Min Integer>} ela deixaria de ser avaliada sem aviso. A ausência é tratada
   * como {@link Optional} logo na entrada. Valor fora da faixa, vazio ({@code ?page=}) ou que não
   * é um inteiro responde {@code 400} no envelope de erro do sistema; ausente, não. O vazio só é
   * recusado porque o {@code GlobalExceptionHandler} registra o binder de {@code page} e
   * {@code size}: sem ele o Spring o converteria em {@code null}.</p>
   */
  @GetMapping
  public ResponseEntity<? extends CollectionResponse<SearchHotelAvailableOutput>> searchHotelAvailableBy(
    @RequestParam(value = "name", required = false, defaultValue = "") final String name,
    @RequestParam(value = "city", required = false, defaultValue = "") final String city,
    @RequestParam(value = "state", required = false, defaultValue = "") final String state,
    @RequestParam(value = "category", required = false, defaultValue = "") final String category,
    @RequestParam(value = "page", required = false)
    @Min(value = 0, message = ApplicationMessage.PAGE_NUMBER_INVALID) final Integer page,
    @RequestParam(value = "size", required = false)
    @Min(value = MIN_PAGE_SIZE, message = ApplicationMessage.PAGE_SIZE_INVALID)
    @Max(value = MAX_PAGE_SIZE, message = ApplicationMessage.PAGE_SIZE_INVALID) final Integer size
  ) {
    final var input = SearchHotelAvailableInput.builder()
      .name(name)
      .category(category)
      .city(city)
      .state(state)
      .build();
    final var pageNumber = Optional.ofNullable(page);
    final var pageSize = Optional.ofNullable(size);
    if (pageNumber.isEmpty() && pageSize.isEmpty()) {
      final var output = this.hotelApplicationService.searchHotelAvailableBy(input);
      return ResponseEntityAdapter.items(output);
    }
    final var pageRequest = PageRequest.of(
      pageNumber.orElse(DEFAULT_PAGE_NUMBER),
      pageSize.orElse(DEFAULT_PAGE_SIZE)
    );
    return ResponseEntityAdapter.page(this.hotelApplicationService.searchHotelAvailablePageBy(input, pageRequest));
  }

  @PostMapping("/booking")
  public ResponseEntity<Response<BookingRoomOutput>> bookingRoom(
    @Valid @RequestBody final BookingRoomInput input
  ) {
    final var output = this.hotelApplicationService.bookingRoomRequest(input);
    return ResponseEntityAdapter.of(output);
  }


}
