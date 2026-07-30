package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.dto.IntakeRequest;
import com.fixy.backend.dto.IntakeResponse;
import com.fixy.backend.model.ServiceCategory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Deuda técnica #1 (ARQUITECTURA_SUPERAPP.md building block #5 / PLAN_SUPERAPP_CLIENTE.md
 * Ola 1 #1): "qué categorías existen" vivía fragmentado en ~5 lugares que se podían
 * desincronizar entre sí (ya pasó un bug real al sumar pastelería). Este test verifica que
 * los puntos que antes tenían su propia lista hardcodeada (LeadAgentService.MVP_CATEGORIES,
 * LeadService.MVP_CATEGORIES, AgentService.intakeJsonSchema, el prompt lead-agent-system.md)
 * ahora derivan todos de ServiceCategory (fuente única) y quedan consistentes entre sí.
 * Vive en este paquete (no en model/) porque PromptLoader es package-private de service.
 */
class ServiceCategoryConsistencyTest {

  @Test
  void allMvpCategoriesAreMarkedAsMvp() {
    assertThat(ServiceCategory.MVP_IDS).containsExactlyInAnyOrder(
        "plomeria", "barometrica", "jardineria", "aires_acondicionados", "pasteleria",
        "decoracion_fiestas", "mandados");
  }

  @Test
  void allIdsIncludesLegacyNonMvpCategoriesAndOtro() {
    // AgentService.intakeJsonSchema necesita reconocer también categorías legacy
    // que Fixy clasifica pero no matchea (electricidad, cerrajería, reparaciones).
    assertThat(ServiceCategory.ALL_IDS_INCLUDING_OTRO).containsExactlyInAnyOrder(
        "plomeria", "electricidad", "cerrajeria", "barometrica", "jardineria",
        "aires_acondicionados", "reparaciones", "pasteleria", "decoracion_fiestas",
        "mandados", "otro");
  }

  @Test
  void agentServiceIntakeJsonSchemaDerivesFromServiceCategory() {
    Map<String, Object> schema = AgentService.intakeJsonSchema();
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    @SuppressWarnings("unchecked")
    Map<String, Object> serviceCategory = (Map<String, Object>) properties.get("serviceCategory");
    @SuppressWarnings("unchecked")
    List<String> categoryEnum = (List<String>) serviceCategory.get("enum");

    assertThat(categoryEnum).containsExactlyInAnyOrderElementsOf(ServiceCategory.ALL_IDS_INCLUDING_OTRO);
  }

  @Test
  void promptMentionsHumanLabelOfEveryMvpCategory() {
    String prompt = PromptLoader.load("prompts/lead-agent-system.md");
    for (String id : ServiceCategory.MVP_IDS) {
      String label = ServiceCategory.humanLabel(id);
      assertThat(prompt)
          .as("el prompt lead-agent-system.md debe mencionar la categoría MVP '%s' (label '%s')", id, label)
          .containsIgnoringCase(label);
    }
  }

  @Test
  void detectFromTextFindsEachCategoryByItsOwnKeyword() {
    assertThat(ServiceCategory.detectFromText("se me rompió la canilla")).contains(ServiceCategory.PLOMERIA);
    assertThat(ServiceCategory.detectFromText("se tapó la cámara séptica")).contains(ServiceCategory.BAROMETRICA);
    assertThat(ServiceCategory.detectFromText("necesito cortar el pasto del jardín")).contains(ServiceCategory.JARDINERIA);
    assertThat(ServiceCategory.detectFromText("el aire acondicionado no enfría")).contains(ServiceCategory.AIRES_ACONDICIONADOS);
    assertThat(ServiceCategory.detectFromText("quiero encargar una torta de cumpleaños")).contains(ServiceCategory.PASTELERIA);
    assertThat(ServiceCategory.detectFromText("se cortó la luz del tablero")).contains(ServiceCategory.ELECTRICIDAD);
    assertThat(ServiceCategory.detectFromText("me quedé afuera, perdí la llave")).contains(ServiceCategory.CERRAJERIA);
    assertThat(ServiceCategory.detectFromText("quiero un arco de globos para ambientar la fiesta"))
        .contains(ServiceCategory.DECORACION_FIESTAS);
    assertThat(ServiceCategory.detectFromText("necesito que alguien me haga un mandado a la farmacia"))
        .contains(ServiceCategory.MANDADOS);
    // Coloquial de corrección (prueba real de Carlos, lead #194): "es aires"
    // tiene que ser detectable para que la corrección determinista funcione.
    assertThat(ServiceCategory.detectFromText("me equivoque es aires"))
        .contains(ServiceCategory.AIRES_ACONDICIONADOS);
    assertThat(ServiceCategory.detectFromText("en realidad es el aire que anda mal"))
        .contains(ServiceCategory.AIRES_ACONDICIONADOS);
    assertThat(ServiceCategory.detectFromText("¿pueden pagar una factura en abitab por mí?"))
        .contains(ServiceCategory.MANDADOS);
    // "comprar X para instalar/comer" sigue en su rubro: el orden de declaración
    // (mandados al final) hace que la keyword específica gane primero.
    assertThat(ServiceCategory.detectFromText("quiero comprar un split y que me lo instalen"))
        .contains(ServiceCategory.AIRES_ACONDICIONADOS);
    assertThat(ServiceCategory.detectFromText("hola, ¿cómo estás?")).isEmpty();
  }

  @Test
  void decoracionYPasteleriaSeDistinguenPorTerminosPropios() {
    // Términos propios de cada rubro clasifican bien en el heurístico:
    assertThat(ServiceCategory.detectFromText("una torta para el evento")).contains(ServiceCategory.PASTELERIA);
    assertThat(ServiceCategory.detectFromText("quiero globos y ambientación para el evento"))
        .contains(ServiceCategory.DECORACION_FIESTAS);

    // Limitación RESUELTA (2026-07-27): "decoración para el cumpleaños" caía
    // en pastelería por orden de keywords; ahora refineAmbiguity desempata en
    // código (señal de ambientación sin señal inequívoca de comida →
    // decoración). El banco de modelos lo midió fallando también en gpt-5-mini,
    // así que la regla dejó de vivir solo en el prompt.
    assertThat(ServiceCategory.detectFromText("decoración con globos para el cumpleaños"))
        .contains(ServiceCategory.DECORACION_FIESTAS);
  }

  @Test
  void humanLabelFallsBackToRawWhenUnrecognizedAndToDefaultWhenBlank() {
    assertThat(ServiceCategory.humanLabel("pasteleria")).isEqualTo("pastelería");
    assertThat(ServiceCategory.humanLabel("categoria-inventada")).isEqualTo("categoria-inventada");
    assertThat(ServiceCategory.humanLabel(null)).isEqualTo("tu pedido");
    assertThat(ServiceCategory.humanLabel("")).isEqualTo("tu pedido");
  }

  @Test
  void classifyStillNormalizesFreeTextToMvpCategoryIncludingPasteleria() {
    ObjectMapper mapper = new ObjectMapper();
    AgentService agentService = new AgentService(mapper, "", "gpt-5-mini");
    IntakeResponse response = agentService.classify(new IntakeRequest(
        "quiero encargar una torta para un cumpleaños en Solymar",
        "Ana", "099111222", "web-app", null, null, null, null, null));
    assertThat(response.serviceCategory()).isEqualTo("pasteleria");
  }

  /**
   * Desempate determinista pastelería/decoración (banco de modelos 2026-07-27,
   * escenario mixto_cumple_decoracion): "cumpleaños" es keyword de pastelería,
   * así que "decoración para el cumpleaños" caía en pastelería en el heurístico
   * Y en los modelos que no siguen la regla del prompt. La regla vive en código.
   */
  @Test
  void decoracionParaUnCumpleanosClasificaDecoracionNoPasteleria() {
    assertThat(ServiceCategory.detectFromText(
        "necesito decoración para el cumpleaños de mi hija, globos y guirnaldas"))
        .contains(ServiceCategory.DECORACION_FIESTAS);
    // Vía id (paths LLM): la extracción dijo pastelería pero el texto es de ambientación.
    assertThat(ServiceCategory.refineCategoryId(
        "quiero decorar el salón para un cumpleaños", "pasteleria"))
        .isEqualTo("decoracion_fiestas");
  }

  @Test
  void tortaParaElCumpleSigueSiendoPasteleriaAunConPalabraDecorar() {
    // "torta" es señal inequívoca de comida: gana pastelería aunque pida decorarla.
    assertThat(ServiceCategory.detectFromText(
        "quiero una torta decorada para el cumpleaños"))
        .contains(ServiceCategory.PASTELERIA);
    assertThat(ServiceCategory.refineCategoryId(
        "una torta con decoración de unicornio", "pasteleria"))
        .isEqualTo("pasteleria");
  }

  @Test
  void refineCategoryIdEsNoOpParaCategoriasNoAmbiguasOIdsDesconocidos() {
    assertThat(ServiceCategory.refineCategoryId("decoración con globos", "plomeria"))
        .isEqualTo("plomeria");
    assertThat(ServiceCategory.refineCategoryId("lo que sea", "categoria-inventada"))
        .isEqualTo("categoria-inventada");
    assertThat(ServiceCategory.refineCategoryId(null, "pasteleria")).isEqualTo("pasteleria");
  }
}
