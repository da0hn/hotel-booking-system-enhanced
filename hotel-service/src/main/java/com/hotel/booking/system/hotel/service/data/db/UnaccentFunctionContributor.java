package com.hotel.booking.system.hotel.service.data.db;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Ensina ao Hibernate a função {@code unaccent} do PostgreSQL, usada pela busca de hotéis do
 * {@code HotelJpaRepository}.
 *
 * <p>O {@code function('unaccent', ...)} genérico do HQL compila, mas o Hibernate assume que
 * o retorno é {@code Object} e recusa a consulta na inicialização do repositório, com
 * {@code Operand of 'like' is of type 'java.lang.Object'}. A falha é de arranque, não de
 * execução — o serviço nem sobe.</p>
 *
 * <p>Registrar a função declara o tipo de retorno e permite chamá-la pelo nome no HQL, sem o
 * invólucro {@code function()}. A descoberta é pelo {@code ServiceLoader}: o arquivo
 * {@code META-INF/services/org.hibernate.boot.model.FunctionContributor} aponta para cá.</p>
 */
public class UnaccentFunctionContributor implements FunctionContributor {

  @Override
  public void contributeFunctions(final FunctionContributions functionContributions) {
    final var texto = functionContributions.getTypeConfiguration()
      .getBasicTypeRegistry()
      .resolve(StandardBasicTypes.STRING);

    functionContributions.getFunctionRegistry().registerNamed("unaccent", texto);
  }

}
