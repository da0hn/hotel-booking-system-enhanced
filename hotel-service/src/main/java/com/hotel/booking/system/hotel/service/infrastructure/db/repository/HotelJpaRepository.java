package com.hotel.booking.system.hotel.service.infrastructure.db.repository;

import com.hotel.booking.system.hotel.service.infrastructure.db.entity.HotelEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HotelJpaRepository extends JpaRepository<HotelEntity, UUID> {

  /**
   * Os hotéis que atendem aos quatro filtros, ignorando caixa e acento. É a base das duas
   * consultas de busca, a em lista e a paginada: elas compartilham o texto de propósito, para
   * que os filtros nunca divirjam entre os dois modos.
   *
   * <p>O {@code lower} sempre esteve aqui; o {@code unaccent} não. Enquanto o banco era
   * MySQL a colação {@code utf8mb4_0900_ai_ci} descartava o acento na comparação sem que
   * ninguém pedisse, e digitar {@code cuiaba} encontrava {@code Cuiabá}. O PostgreSQL
   * compara texto byte a byte: o mesmo comportamento agora é uma chamada de função explícita
   * dos dois lados da comparação, contra a extensão que a V011 instala.</p>
   */
  String AVAILABLE_HOTELS_BY_PARAMETERS = """
    SELECT hotel FROM HotelEntity hotel
    LEFT JOIN hotel.category category
    LEFT JOIN hotel.locality locality
    WHERE
      ( unaccent(lower(hotel.name)) LIKE CONCAT('%', unaccent(lower(:name)), '%') or :name is null or :name = '' ) AND
      ( unaccent(lower(category.name)) LIKE CONCAT('%', unaccent(lower(:category)), '%') or :category is null or :category = '' ) AND
      ( unaccent(lower(locality.city)) LIKE CONCAT('%', unaccent(lower(:city)), '%') or :city is null or :city = '' ) AND
      ( unaccent(lower(locality.state)) LIKE CONCAT('%', unaccent(lower(:state)), '%') or :state is null or :state = '' )
    """;

  /**
   * Busca hotéis por qualquer combinação dos quatro filtros, sem ordem nem limite: é a resposta de
   * lista de {@code GET /hotel}, que não muda.
   */
  @Query(AVAILABLE_HOTELS_BY_PARAMETERS)
  List<HotelEntity> findAllAvailableHotelByParameters(String name, String category, String city, String state);

  /**
   * Uma página da mesma busca, ordenada por {@code name} e, no empate, por {@code id}.
   *
   * <p>O retorno é {@link Slice}, e não {@code Page}, de propósito: o Spring Data pede
   * {@code size + 1} linhas e decide o {@code hasNext} pela sobra, sem a consulta {@code COUNT}
   * que um {@code Page} faria a cada requisição. O desempate por {@code id} é o que torna a
   * paginação estável: sem ele, hotéis de mesmo nome podem trocar de lugar entre duas
   * consultas, e um deles aparece em duas páginas ou em nenhuma.</p>
   *
   * <p>O {@code ORDER BY} está no texto da consulta, e não no {@code Pageable}, para que a
   * ordem não dependa de quem chama: um {@code Pageable} sem {@code Sort} não a desfaz.</p>
   */
  @Query(AVAILABLE_HOTELS_BY_PARAMETERS + " ORDER BY hotel.name, hotel.id")
  Slice<HotelEntity> findAvailableHotelPageByParameters(
    String name,
    String category,
    String city,
    String state,
    Pageable pageable
  );
}
