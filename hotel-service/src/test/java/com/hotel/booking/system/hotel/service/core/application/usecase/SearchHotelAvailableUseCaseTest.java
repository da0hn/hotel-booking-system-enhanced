package com.hotel.booking.system.hotel.service.core.application.usecase;

import com.hotel.booking.system.commons.core.domain.pagination.Page;
import com.hotel.booking.system.commons.core.domain.pagination.PageRequest;
import com.hotel.booking.system.commons.core.domain.valueobject.HotelId;
import com.hotel.booking.system.hotel.service.core.application.dto.SearchHotelAvailableInput;
import com.hotel.booking.system.hotel.service.core.application.mapper.HotelUseCaseMapperImpl;
import com.hotel.booking.system.hotel.service.core.ports.api.usecase.SearchHotelAvailableUseCase;
import com.hotel.booking.system.hotel.service.core.ports.spi.queries.SearchHotelAvailableQueryResult;
import com.hotel.booking.system.hotel.service.core.ports.spi.queries.SearchHotelAvailableRoomQueryResult;
import com.hotel.booking.system.hotel.service.core.ports.spi.repository.HotelRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

@Tags({@Tag("unit")})
@DisplayName("Search available Hotel use case tests")
@ExtendWith(MockitoExtension.class)
class SearchHotelAvailableUseCaseTest {

  private SearchHotelAvailableUseCase sut;
  @Mock
  private HotelRepository hotelRepository;


  @BeforeEach
  void setUp() {
    this.sut = new SearchHotelAvailableUseCaseImpl(
      this.hotelRepository,
      new HotelUseCaseMapperImpl()
    );
  }

  @Test
  @DisplayName("Should search available Hotel")
  void test1() {

    Mockito.doReturn(List.of(new FakeSearchHotelAvailableQueryResult()))
      .when(this.hotelRepository)
      .searchHotelAvailableBy("Pousada", null, null, null);

    final var output = this.sut.execute(
      SearchHotelAvailableInput.builder()
        .name("Pousada")
        .build()
    );

    Assertions.assertThat(output).hasSize(1);
    Assertions.assertThat(output).first()
      .usingRecursiveAssertion()
      .hasNoNullFields();
  }

  @Test
  @DisplayName("Should search available Hotel by page keeping the page information")
  void test2() {
    final var request = PageRequest.of(1, 2);
    Mockito.doReturn(Page.withoutTotal(List.of(new FakeSearchHotelAvailableQueryResult()), request, true))
      .when(this.hotelRepository)
      .searchHotelAvailableBy("Pousada", null, null, null, request);

    final var output = this.sut.execute(
      SearchHotelAvailableInput.builder()
        .name("Pousada")
        .build(),
      request
    );

    Assertions.assertThat(output.content()).hasSize(1);
    Assertions.assertThat(output.content()).first()
      .usingRecursiveAssertion()
      .hasNoNullFields();
    Assertions.assertThat(output.number()).isEqualTo(1);
    Assertions.assertThat(output.size()).isEqualTo(2);
    Assertions.assertThat(output.hasNext()).isTrue();
    Assertions.assertThat(output.totalElements()).isEmpty();
  }

  @Test
  @DisplayName("Should return an empty page, not fail, when the page is past the end")
  void test3() {
    final var request = PageRequest.of(9, 20);
    Mockito.doReturn(Page.withoutTotal(List.<SearchHotelAvailableQueryResult>of(), request, false))
      .when(this.hotelRepository)
      .searchHotelAvailableBy(null, null, null, null, request);

    final var output = this.sut.execute(SearchHotelAvailableInput.builder().build(), request);

    Assertions.assertThat(output.content()).isEmpty();
    Assertions.assertThat(output.number()).isEqualTo(9);
    Assertions.assertThat(output.hasNext()).isFalse();
  }

  private static class FakeSearchHotelAvailableQueryResult implements SearchHotelAvailableQueryResult {
    @Override
    public HotelId getHotelId() {
      return HotelId.of("23ab46cd-cea3-4088-90c0-f15ba85164ff");
    }

    @Override
    public String getHotelName() {
      return "Hotel";
    }

    @Override
    public String getHotelDescription() {
      return "Descrição";
    }

    @Override
    public String getHotelCep() {
      return "78135-000";
    }

    @Override
    public String getHotelStreet() {
      return "Rua A, 101";
    }

    @Override
    public String getHotelCategoryName() {
      return "Hotel";
    }

    @Override
    public String getHotelCity() {
      return "Cuiabá";
    }

    @Override
    public String getHotelState() {
      return "Mato Grosso";
    }

    @Override
    public String getHotelCountry() {
      return "Brasil";
    }

    @Override
    public List<SearchHotelAvailableRoomQueryResult> getRooms() {
      return new ArrayList<>();
    }
  }
}
