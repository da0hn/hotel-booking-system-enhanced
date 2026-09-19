package com.hotel.booking.system.commons.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante que o módulo compartilhado permaneça independente dos microsserviços.
 */
@AnalyzeClasses(
  packages = "com.hotel.booking.system.commons",
  importOptions = DoNotIncludeTests.class
)
@DisplayName("Arquitetura do commons")
class CommonsArchitectureTest {

  @ArchTest
  static final ArchRule commonsDoesNotDependOnServiceInternals = noClasses()
    .should().dependOnClassesThat().resideInAnyPackage(
      "com.hotel.booking.system.hotel.service..",
      "com.hotel.booking.system.booking.service..",
      "com.hotel.booking.system.payment.service..",
      "com.hotel.booking.system.customer.service.."
    );

  @ArchTest
  static void importsNonEmptyProductionScope(final JavaClasses classes) {
    assertThat(classes.stream()).isNotEmpty();
    assertPackageIsPresent(classes, "com.hotel.booking.system.commons.core");
    assertPackageIsPresent(classes, "com.hotel.booking.system.commons.application");
  }

  private static void assertPackageIsPresent(final JavaClasses classes, final String packagePrefix) {
    assertThat(classes.stream().anyMatch(type -> type.getPackageName().startsWith(packagePrefix)))
      .as("Nenhuma classe de produção foi importada para %s", packagePrefix)
      .isTrue();
  }

}
