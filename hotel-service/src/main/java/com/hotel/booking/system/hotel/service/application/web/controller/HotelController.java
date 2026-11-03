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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import org.springframework.beans.propertyeditors.CustomNumberEditor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
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

  /**
   * O que um {@code page} ou {@code size} presente e vazio vira antes da validação. Nenhuma
   * restrição de {@code page} ou {@code size} aceita este valor, que não tem outra função.
   */
  private static final int EMPTY_PARAMETER = Integer.MIN_VALUE;

  private final HotelApplicationService hotelApplicationService;

  /**
   * O Spring converte a string vazia em {@code null} para um {@code Integer}, e a validação não
   * avalia {@code null}: sem este editor, {@code ?page=} valeria como parâmetro ausente e devolveria
   * a lista inteira. Ele troca o vazio por {@link #EMPTY_PARAMETER}, que o {@code @Min} recusa com a
   * mesma chave de qualquer valor fora da faixa, e a violação sai junto com as dos outros parâmetros.
   * Vale só para {@code page} e {@code size}: os filtros de texto vazios continuam contando como
   * ausentes.
   */
  @InitBinder({"page", "size"})
  void rejectEmptyPageParameters(final WebDataBinder binder) {
    binder.registerCustomEditor(Integer.class, new EmptyIsInvalidIntegerEditor());
  }

  @PostMapping
  public ResponseEntity<Response<RegisterHotelOutput>> register(@RequestBody final RegisterHotelInput input) {
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
   * é um inteiro responde {@code 400} no envelope de erro do sistema; ausente, não.</p>
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
  public ResponseEntity<Response<BookingRoomOutput>> bookingRoom(@RequestBody final BookingRoomInput input) {
    final var output = this.hotelApplicationService.bookingRoomRequest(input);
    return ResponseEntityAdapter.of(output);
  }

  /**
   * Converte texto em {@code Integer} como o Spring faz por padrão, exceto o texto vazio, que vira
   * {@link #EMPTY_PARAMETER} em vez de {@code null}.
   */
  private static final class EmptyIsInvalidIntegerEditor extends CustomNumberEditor {

    EmptyIsInvalidIntegerEditor() {
      super(Integer.class, false);
    }

    @Override
    public void setAsText(final String text) {
      if (text.isEmpty()) {
        this.setValue(EMPTY_PARAMETER);
        return;
      }
      super.setAsText(text);
    }
  }
}
