package com.hotel.booking.system.hotel.service.data.db.repository;

import com.hotel.booking.system.hotel.service.data.db.entity.HotelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HotelJpaRepository extends JpaRepository<HotelEntity, UUID> {

  /**
   * Busca hotéis por qualquer combinação dos quatro filtros, ignorando caixa e acento.
   *
   * <p>O {@code lower} sempre esteve aqui; o {@code unaccent} não. Enquanto o banco era
   * MySQL a colação {@code utf8mb4_0900_ai_ci} descartava o acento na comparação sem que
   * ninguém pedisse, e digitar {@code cuiaba} encontrava {@code Cuiabá}. O PostgreSQL
   * compara texto byte a byte: o mesmo comportamento agora é uma chamada de função explícita
   * dos dois lados da comparação, contra a extensão que a V011 instala.</p>
   */
  @Query("""
    SELECT hotel FROM HotelEntity hotel
    LEFT JOIN hotel.category category
    LEFT JOIN hotel.locality locality
    WHERE
      ( unaccent(lower(hotel.name)) LIKE CONCAT('%', unaccent(lower(:name)), '%') or :name is null or :name = '' ) AND
      ( unaccent(lower(category.name)) LIKE CONCAT('%', unaccent(lower(:category)), '%') or :category is null or :category = '' ) AND
      ( unaccent(lower(locality.city)) LIKE CONCAT('%', unaccent(lower(:city)), '%') or :city is null or :city = '' ) AND
      ( unaccent(lower(locality.state)) LIKE CONCAT('%', unaccent(lower(:state)), '%') or :state is null or :state = '' )
    """)
  List<HotelEntity> findAllAvailableHotelByParameters(String name, String category, String city, String state);
}
