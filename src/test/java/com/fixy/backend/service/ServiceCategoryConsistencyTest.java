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
  void allMvpCategoriesAreMarkedAsMvpAndIncludePasteleria() {
    assertThat(ServiceCategory.MVP_IDS).containsExactlyInAnyOrder(
        "plomeria", "barometrica", "jardineria", "aires_acondicionados", "pasteleria");
  }

  @Test
  void allIdsIncludesLegacyNonMvpCategoriesAndOtro() {
    // AgentService.intakeJsonSchema necesita reconocer también categorías legacy
    // que Fixy clasifica pero no matchea (electricidad, cerrajería, reparaciones).
    assertThat(ServiceCategory.ALL_IDS_INCLUDING_OTRO).containsExactlyInAnyOrder(
        "plomeria", "electricidad", "cerrajeria", "barometrica", "jardineria",
        "aires_acondicionados", "reparaciones", "pasteleria", "otro");
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
    assertThat(ServiceCategory.detectFromText("hola, ¿cómo estás?")).isEmpty();
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
}
