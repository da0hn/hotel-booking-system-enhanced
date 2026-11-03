package com.hotel.booking.system.commons.application.dto;

/**
 * Envelope de uma página: o de lista ({@code success} e {@code data}) mais o campo {@code page}.
 * Ser um {@link CollectionResponse} é de propósito: quem aceita uma lista aceita uma página, e a
 * resposta de lista segue sem {@code page}.
 */
public interface PageResponse<T> extends CollectionResponse<T> {

  PageInfo getPage();

}
