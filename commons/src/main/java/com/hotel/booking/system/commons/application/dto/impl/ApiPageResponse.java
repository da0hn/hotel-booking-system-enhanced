package com.hotel.booking.system.commons.application.dto.impl;

import com.hotel.booking.system.commons.application.dto.PageInfo;
import com.hotel.booking.system.commons.application.dto.PageResponse;
import com.hotel.booking.system.commons.core.domain.pagination.Page;

import java.util.Collection;

public class ApiPageResponse<T> extends ApiCollectionResponse<T> implements PageResponse<T> {

  private final PageInfo page;

  public ApiPageResponse(
    final Collection<T> data,
    final boolean success,
    final PageInfo page
  ) {
    super(data, success);
    this.page = page;
  }

  public static <T> PageResponse<T> of(final Page<T> page) {
    return new ApiPageResponse<>(page.content(), true, PageInfo.of(page));
  }

  @Override
  public PageInfo getPage() {
    return this.page;
  }

}
