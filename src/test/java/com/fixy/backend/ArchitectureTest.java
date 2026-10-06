package com.fixy.backend;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import java.util.List;

/**
 * Core Fase 3 (ver CORE_FASE3_CONTRATO.md, pieza 1): fronteras de módulo.
 *
 * <p>{@code core.*} es el código que no sabe qué vertical sirve (gateway de LLM, prompts, política
 * como interfaz, dominio como datos, el caso y su memoria de turnos); {@code domain.fixy} es el
 * vertical "servicios del hogar" y es lo único que puede conocer su vocabulario. Las reglas existen
 * para que estas fronteras no se erosionen sin que alguien lo decida a propósito.
 *
 * <p>Se excluyen los tests del análisis ({@code DoNotIncludeTests}): sin eso R3 falla por
 * {@code TurnPolicyDelegationTest}, que llama a {@code HomeServicesPolicy} a propósito.
 */
@AnalyzeClasses(packages = "com.fixy.backend", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /**
   * Los únicos servicios de {@code ..service..} que {@code ..domain.fixy..} puede usar HOY
   * ({@code HomeServicesTurnPolicy}: historial de mensajes y detección de zona del intake). La lista
   * existe para que no CREZCA: agregar una entrada es una decisión de arquitectura, no un import más.
   */
  private static final List<String> SERVICIOS_PERMITIDOS_A_DOMAIN_FIXY =
      List.of("LeadMessageService", "AgentService");

  /** R1: el core no conoce el vertical. */
  @ArchTest
  static final ArchRule core_no_depende_de_domain_fixy =
      noClasses().that().resideInAPackage("..core..")
          .should().dependOnClassesThat().resideInAPackage("..domain.fixy..");

  /** R2: el core no depende de las capas strangler (servicios, controllers, DTOs). */
  @ArchTest
  static final ArchRule core_no_depende_de_service_controller_ni_dto =
      noClasses().that().resideInAPackage("..core..")
          .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..", "..dto..");

  /**
   * R3: {@code HomeServicesPolicy} (los estáticos del vertical) solo se referencia desde
   * {@code domain.fixy} y desde {@code LeadAgentService}, cuyos delegadores de test la usan.
   * Congela el strangler: ningún servicio nuevo puede engancharse a ella.
   */
  @ArchTest
  static final ArchRule home_services_policy_solo_desde_domain_fixy_y_el_loop =
      noClasses()
          .that().resideOutsideOfPackage("..domain.fixy..")
          .and().haveNameNotMatching("com\\.fixy\\.backend\\.service\\.LeadAgentService(\\$.*)?")
          .should().dependOnClassesThat().haveFullyQualifiedName("com.fixy.backend.domain.fixy.HomeServicesPolicy");

  /** R4: sin ciclos entre los cuatro módulos. */
  @ArchTest
  static final ArchRule modulos_sin_ciclos =
      slices().assignedFrom(modulos()).should().beFreeOfCycles();

  /** R5: domain.fixy solo depende, dentro de {@code service}, de la lista fija de arriba. */
  @ArchTest
  static final ArchRule domain_fixy_solo_usa_los_servicios_permitidos =
      noClasses().that().resideInAPackage("..domain.fixy..")
          .should().dependOnClassesThat(
              resideInAPackage("..service..").and(not(servicioPermitido())));

  private static DescribedPredicate<JavaClass> servicioPermitido() {
    return new DescribedPredicate<>("uno de " + SERVICIOS_PERMITIDOS_A_DOMAIN_FIXY) {
      @Override
      public boolean test(JavaClass c) {
        JavaClass top = c;
        while (top.getEnclosingClass().isPresent()) {
          top = top.getEnclosingClass().get();
        }
        return SERVICIOS_PERMITIDOS_A_DOMAIN_FIXY.contains(top.getSimpleName());
      }
    };
  }

  private static SliceAssignment modulos() {
    return new SliceAssignment() {
      @Override
      public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
        String pkg = javaClass.getPackageName();
        for (String modulo : List.of("core.agent", "core.cases", "core.domain", "domain.fixy")) {
          String prefijo = "com.fixy.backend." + modulo;
          if (pkg.equals(prefijo) || pkg.startsWith(prefijo + ".")) {
            return SliceIdentifier.of(modulo);
          }
        }
        return SliceIdentifier.ignore();
      }

      @Override
      public String getDescription() {
        return "módulos core.agent, core.cases, core.domain y domain.fixy";
      }
    };
  }
}
