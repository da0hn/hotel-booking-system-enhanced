package com.hotel.booking.system.hotel.service.architecture;

import com.hotel.booking.system.commons.core.application.annotation.DomainService;
import com.hotel.booking.system.commons.core.application.annotation.Listener;
import com.hotel.booking.system.commons.core.application.annotation.Mapper;
import com.hotel.booking.system.commons.core.application.annotation.Publisher;
import com.hotel.booking.system.commons.core.application.annotation.UseCase;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica as fronteiras hexagonais do serviço de hotéis sobre o bytecode de produção.
 *
 * <p>Os DTOs de {@code core.application} continuam disponíveis para os contratos das
 * portas. O que fica proibido nas portas são implementações concretas de casos de uso,
 * handlers e mappers. Os estereótipos declarativos do Spring identificam os componentes
 * que o contexto pode descobrir sem permitir dependência da camada {@code infrastructure}.</p>
 */
@AnalyzeClasses(
  packages = "com.hotel.booking.system.hotel.service",
  importOptions = DoNotIncludeTests.class
)
@DisplayName("Arquitetura do hotel-service")
class HotelArchitectureTest {

  private static final String ROOT = "com.hotel.booking.system.hotel.service";
  private static final String CORE = ROOT + ".core";
  private static final String CORE_DOMAIN = CORE + ".domain..";
  private static final String CORE_APPLICATION = CORE + ".application..";
  private static final String CORE_PORTS = CORE + ".ports..";
  private static final String INFRASTRUCTURE = ROOT + ".infrastructure..";
  private static final String APPLICATION = ROOT + ".application..";

  @ArchTest
  static final ArchRule domainDoesNotDependOnOtherLayers = noClasses()
    .that().resideInAnyPackage(CORE_DOMAIN)
    .should().dependOnClassesThat().resideInAnyPackage(CORE_APPLICATION, CORE_PORTS, INFRASTRUCTURE, APPLICATION);

  @ArchTest
  static final ArchRule coreDoesNotDependOnOuterLayers = noClasses()
    .that().resideInAnyPackage(CORE + "..")
    .should().dependOnClassesThat().resideInAnyPackage(INFRASTRUCTURE, APPLICATION);

  @ArchTest
  static final ArchRule portsDoNotDependOnApplicationImplementations = noClasses()
    .that().resideInAnyPackage(CORE_PORTS)
    .should().dependOnClassesThat().resideInAnyPackage(
      CORE + ".application.usecase..",
      CORE + ".application.messaging..",
      CORE + ".application.mapper..",
      CORE + ".application.service.."
    );

  @ArchTest
  static final ArchRule applicationCoreUsesPortsInsteadOfAdapters = noClasses()
    .that().resideInAnyPackage(CORE_APPLICATION)
    .should().dependOnClassesThat().resideInAnyPackage(INFRASTRUCTURE, APPLICATION);

  @ArchTest
  static final ArchRule useCasesAreAnnotated = classes()
    .that().resideInAnyPackage(CORE + ".application.usecase..")
    .should().beAnnotatedWith(UseCase.class);

  @ArchTest
  static final ArchRule mappersAreAnnotated = classes()
    .that().resideInAnyPackage(CORE + ".application.mapper..", ROOT + ".infrastructure.db.mapper.impl..")
    .should().beAnnotatedWith(Mapper.class);

  @ArchTest
  static final ArchRule listenersAreAnnotated = classes()
    .that().resideInAnyPackage(ROOT + ".infrastructure.messaging.listener..")
    .should().beAnnotatedWith(Listener.class);

  @ArchTest
  static final ArchRule publishersAreAnnotated = classes()
    .that().resideInAnyPackage(ROOT + ".infrastructure.messaging.publisher..")
    .should().beAnnotatedWith(Publisher.class)
    .allowEmptyShould(true);

  @ArchTest
  static final ArchRule domainServicesAreAnnotated = classes()
    .that().resideInAnyPackage(CORE + ".application.service..")
    .should().beAnnotatedWith(DomainService.class)
    .allowEmptyShould(true);

  @ArchTest
  static final ArchRule infrastructureConfigurationDoesNotRegisterStereotypedComponents = noClasses()
    .that().resideInAnyPackage(ROOT + ".infrastructure.configuration..")
    .should().dependOnClassesThat().resideInAnyPackage(
      CORE + ".application.usecase..",
      CORE + ".application.mapper..",
      CORE + ".application.service..",
      ROOT + ".infrastructure.db.mapper.impl..",
      ROOT + ".infrastructure.messaging.listener..",
      ROOT + ".infrastructure.messaging.publisher.."
    );

  @ArchTest
  static final ArchRule infrastructureDoesNotDependOnOuterApplication = noClasses()
    .that().resideInAnyPackage(INFRASTRUCTURE)
    .should().dependOnClassesThat().resideInAnyPackage(APPLICATION);

  @ArchTest
  static void usesInfrastructureForAdaptersAndConfiguration(final JavaClasses classes) {
    assertThat(classes.stream()
      .filter(type -> type.getPackageName().startsWith(ROOT + ".data.")
        || type.getPackageName().startsWith(ROOT + ".application.configuration.")))
      .as("A camada de adapters e configuração deve estar em infrastructure")
      .isEmpty();
  }

  @ArchTest
  static final ArchRule serviceDoesNotDependOnOtherServices = noClasses()
    .should().dependOnClassesThat().resideInAnyPackage(
      "com.hotel.booking.system.booking.service..",
      "com.hotel.booking.system.payment.service..",
      "com.hotel.booking.system.customer.service.."
    );

  @ArchTest
  static void importsNonEmptyProductionScope(final JavaClasses classes) {
    assertThat(classes.stream()).isNotEmpty();
    assertPackageIsPresent(classes, CORE + ".domain");
    assertPackageIsPresent(classes, CORE + ".application");
    assertPackageIsPresent(classes, CORE + ".ports");
    assertPackageIsPresent(classes, INFRASTRUCTURE.substring(0, INFRASTRUCTURE.length() - 2));
  }

  private static void assertPackageIsPresent(final JavaClasses classes, final String packagePrefix) {
    assertThat(classes.stream().anyMatch(type -> type.getPackageName().startsWith(packagePrefix)))
      .as("Nenhuma classe de produção foi importada para %s", packagePrefix)
      .isTrue();
  }

}
