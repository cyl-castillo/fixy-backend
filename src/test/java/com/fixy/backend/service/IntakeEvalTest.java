package com.fixy.backend.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.dto.IntakeRequest;
import com.fixy.backend.dto.IntakeResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Eval harness (pieza c) de la modernización del cerebro IA: corre un golden set de intakes
 * realistas en español rioplatense contra el clasificador HEURÍSTICO (sin API key -> AgentService
 * cae directo a classifyHeuristically) y mide accuracy por campo.
 *
 * Los thresholds están fijados apenas debajo de la línea base medida en esta tanda para que el
 * test documente el piso real sin ser flaky. Si una mejora al heurístico sube el accuracy, subir
 * también el threshold para que el test siga siendo una guardia real (no un check inerte).
 *
 * Para correr el mismo golden set contra el LLM real (gpt-5-mini u otro), ver
 * IntakeEvalLlmRunnerTest en este mismo paquete (requiere OPENAI_API_KEY, no corre en CI).
 */
class IntakeEvalTest {

  private static final String GOLDEN_SET_PATH = "eval/intake-golden.jsonl";

  // Línea base medida contra el golden set actual (32 casos). Actualizada 2026-07-05 tras
  // corregir 2 etiquetas de urgencia del golden set que estaban semánticamente mal (c13 y c14
  // describían daño activo/emergencia explícita y estaban marcadas "baja" -- ver el JSONL para el
  // detalle; el golden debe reflejar la verdad del producto, no el comportamiento del heurístico):
  //   categoria: 96.9% (31/32) -- miss real: "poda de arboles" no matchea keywords de jardineria
  //   zona:      87.5% (28/32) -- miss real: detectArea() encuentra "solymar" como substring de
  //              "colinas de solymar"/"lomas de solymar" porque itera CIUDAD_DE_LA_COSTA_ZONES en
  //              orden y "solymar" aparece antes en la lista -- bug real de matching por substring
  //   urgencia:  87.5% (28/32), bajó de 90.6% (29/32) con la corrección de etiquetas -- misses:
  //              (a) detectUrgency() hace contains("urgente") sin manejar negación ("no es
  //              urgente"/"no es nada urgente" arman alta igual -- casos c12 y c30);
  //              (b) detectUrgency() no reconoce "emergencia" como marcador de alta (c14, antes
  //              enmascarado por la etiqueta incorrecta del golden set -- bug real, no arreglado
  //              en esta tanda porque el foco fue el clasificador LLM, no el heurístico).
  // Thresholds fijados unos puntos por debajo de la línea base medida para documentar el piso
  // real sin flakiness ante variaciones menores. Si se arregla alguno de estos bugs del
  // heurístico, subir el threshold correspondiente para que el test siga siendo una guardia real.
  private static final double CATEGORY_THRESHOLD = 0.90;
  private static final double AREA_THRESHOLD = 0.80;
  private static final double URGENCY_THRESHOLD = 0.85;

  // package-private (no private): reutilizado por IntakeEvalLlmRunnerTest para correr el
  // mismo golden set contra el LLM real.
  record GoldenCase(String id, String message, String expectedCategory,
      String expectedArea, String expectedUrgency) {}

  @Test
  void heuristicClassifierMeetsBaselineAccuracy() throws IOException {
    List<GoldenCase> cases = loadGoldenSet();
    assertTrue(cases.size() >= 25, "el golden set debe tener al menos 25 casos, tiene " + cases.size());

    AgentService service = new AgentService(new ObjectMapper(), "", "gpt-5-mini");

    int categoryHits = 0;
    int areaHits = 0;
    int urgencyHits = 0;
    List<String> categoryMisses = new ArrayList<>();
    List<String> areaMisses = new ArrayList<>();
    List<String> urgencyMisses = new ArrayList<>();

    for (GoldenCase c : cases) {
      IntakeRequest request = new IntakeRequest(c.message(), null, null, "web-app");
      IntakeResponse response = service.classify(request);

      boolean categoryMatch = response.serviceCategory().equalsIgnoreCase(c.expectedCategory());
      boolean areaMatch = response.area().equalsIgnoreCase(c.expectedArea());
      boolean urgencyMatch = response.urgency().equalsIgnoreCase(c.expectedUrgency());

      if (categoryMatch) {
        categoryHits++;
      } else {
        categoryMisses.add(formatMiss(c.id(), c.message(), "categoria", c.expectedCategory(), response.serviceCategory()));
      }
      if (areaMatch) {
        areaHits++;
      } else {
        areaMisses.add(formatMiss(c.id(), c.message(), "zona", c.expectedArea(), response.area()));
      }
      if (urgencyMatch) {
        urgencyHits++;
      } else {
        urgencyMisses.add(formatMiss(c.id(), c.message(), "urgencia", c.expectedUrgency(), response.urgency()));
      }
    }

    int total = cases.size();
    double categoryAccuracy = (double) categoryHits / total;
    double areaAccuracy = (double) areaHits / total;
    double urgencyAccuracy = (double) urgencyHits / total;

    printReport(total, categoryAccuracy, areaAccuracy, urgencyAccuracy, categoryMisses, areaMisses, urgencyMisses);

    assertTrue(categoryAccuracy >= CATEGORY_THRESHOLD,
        "accuracy de categoria %.1f%% por debajo del threshold %.1f%%".formatted(categoryAccuracy * 100, CATEGORY_THRESHOLD * 100));
    assertTrue(areaAccuracy >= AREA_THRESHOLD,
        "accuracy de zona %.1f%% por debajo del threshold %.1f%%".formatted(areaAccuracy * 100, AREA_THRESHOLD * 100));
    assertTrue(urgencyAccuracy >= URGENCY_THRESHOLD,
        "accuracy de urgencia %.1f%% por debajo del threshold %.1f%%".formatted(urgencyAccuracy * 100, URGENCY_THRESHOLD * 100));
  }

  private String formatMiss(String id, String message, String field, String expected, String got) {
    return "[%s] %s -> esperado(%s)='%s' obtenido='%s' | mensaje: \"%s\"".formatted(id, field, field, expected, got, message);
  }

  private void printReport(int total, double categoryAcc, double areaAcc, double urgencyAcc,
      List<String> categoryMisses, List<String> areaMisses, List<String> urgencyMisses) {
    System.out.println();
    System.out.println("=== INTAKE EVAL HARNESS (heuristico, " + total + " casos) ===");
    System.out.printf("categoria: %.1f%% (%d/%d)%n", categoryAcc * 100, total - categoryMisses.size(), total);
    System.out.printf("zona:      %.1f%% (%d/%d)%n", areaAcc * 100, total - areaMisses.size(), total);
    System.out.printf("urgencia:  %.1f%% (%d/%d)%n", urgencyAcc * 100, total - urgencyMisses.size(), total);
    if (!categoryMisses.isEmpty()) {
      System.out.println("--- fallos de categoria ---");
      categoryMisses.forEach(System.out::println);
    }
    if (!areaMisses.isEmpty()) {
      System.out.println("--- fallos de zona ---");
      areaMisses.forEach(System.out::println);
    }
    if (!urgencyMisses.isEmpty()) {
      System.out.println("--- fallos de urgencia ---");
      urgencyMisses.forEach(System.out::println);
    }
    System.out.println("=== fin del reporte ===");
    System.out.println();
  }

  static List<GoldenCase> loadGoldenSet() throws IOException {
    ClassPathResource resource = new ClassPathResource(GOLDEN_SET_PATH);
    ObjectMapper mapper = new ObjectMapper();
    List<GoldenCase> cases = new ArrayList<>();
    try (InputStream in = resource.getInputStream()) {
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String line : content.split("\n")) {
        if (line.isBlank()) continue;
        JsonNode node = mapper.readTree(line);
        cases.add(new GoldenCase(
            node.path("id").asText(),
            node.path("message").asText(),
            node.path("expectedCategory").asText(),
            node.path("expectedArea").asText(),
            node.path("expectedUrgency").asText()
        ));
      }
    }
    return cases;
  }
}
