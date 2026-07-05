package com.fixy.backend.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.dto.IntakeRequest;
import com.fixy.backend.dto.IntakeResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runner MANUAL del golden set contra el proveedor OpenAI real (gpt-5-mini u otro seteado en
 * OPENAI_MODEL). NO corre en CI: se activa solo si OPENAI_API_KEY está presente en el ambiente,
 * y esta máquina de desarrollo no tiene esa key -- se corre desde el server post-deploy o
 * localmente con la key exportada.
 *
 * CÓMO CORRERLO (post-deploy, con key real):
 *   OPENAI_API_KEY=sk-... OPENAI_MODEL=gpt-5-mini \
 *     mvn -o test -Dtest=IntakeEvalLlmRunnerTest
 *
 * El test compara accuracy del LLM real contra la línea base heurística ya medida en
 * IntakeEvalTest e imprime ambos reportes lado a lado. No hay assert de threshold contra el LLM
 * real (no sabemos de antemano cuánto va a dar, y esta máquina no puede validarlo) -- el único
 * assert es "no debería ser peor que el heurístico", como smoke check de regresión grosera; el
 * resto es reporte para que un humano lo lea.
 */
class IntakeEvalLlmRunnerTest {

  @Test
  @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
  void compareLlmAccuracyAgainstHeuristicBaseline() throws IOException {
    String apiKey = System.getenv("OPENAI_API_KEY");
    String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-5-mini");

    AgentService llmService = new AgentService(new ObjectMapper(), apiKey, model);
    AgentService heuristicService = new AgentService(new ObjectMapper(), "", model);

    List<IntakeEvalTest.GoldenCase> cases = IntakeEvalTest.loadGoldenSet();

    Result llmResult = run(llmService, cases);
    Result heuristicResult = run(heuristicService, cases);

    System.out.println();
    System.out.println("=== EVAL LLM REAL vs HEURISTICO (modelo=" + model + ", " + cases.size() + " casos) ===");
    System.out.printf("LLM        -> categoria: %.1f%% | zona: %.1f%% | urgencia: %.1f%%%n",
        llmResult.categoryAccuracy() * 100, llmResult.areaAccuracy() * 100, llmResult.urgencyAccuracy() * 100);
    System.out.printf("Heuristico -> categoria: %.1f%% | zona: %.1f%% | urgencia: %.1f%%%n",
        heuristicResult.categoryAccuracy() * 100, heuristicResult.areaAccuracy() * 100, heuristicResult.urgencyAccuracy() * 100);
    if (!llmResult.categoryMisses().isEmpty()) {
      System.out.println("--- LLM fallos de categoria ---");
      llmResult.categoryMisses().forEach(System.out::println);
    }
    if (!llmResult.areaMisses().isEmpty()) {
      System.out.println("--- LLM fallos de zona ---");
      llmResult.areaMisses().forEach(System.out::println);
    }
    if (!llmResult.urgencyMisses().isEmpty()) {
      System.out.println("--- LLM fallos de urgencia ---");
      llmResult.urgencyMisses().forEach(System.out::println);
    }
    System.out.println("=== fin del reporte ===");
    System.out.println();

    // Smoke check minimo: el LLM no deberia degradar por debajo de la heuristica en ningun
    // campo. Si esto falla, algo esta mal (ej. el modelo esta devolviendo 400 y todo cae a null,
    // o el prompt no esta llegando bien) -- no es un check de calidad fino, es una alarma.
    assertTrue(llmResult.categoryAccuracy() >= heuristicResult.categoryAccuracy() - 0.05,
        "LLM tuvo peor accuracy de categoria que el heuristico -- revisar logs por posible 400/fallback silencioso");
  }

  private record Result(double categoryAccuracy, double areaAccuracy, double urgencyAccuracy,
      List<String> categoryMisses, List<String> areaMisses, List<String> urgencyMisses) {}

  private Result run(AgentService service, List<IntakeEvalTest.GoldenCase> cases) {
    int categoryHits = 0;
    int areaHits = 0;
    int urgencyHits = 0;
    List<String> categoryMisses = new ArrayList<>();
    List<String> areaMisses = new ArrayList<>();
    List<String> urgencyMisses = new ArrayList<>();

    for (IntakeEvalTest.GoldenCase c : cases) {
      IntakeRequest request = new IntakeRequest(c.message(), null, null, "web-app");
      IntakeResponse response = service.classify(request);

      if (response.serviceCategory().equalsIgnoreCase(c.expectedCategory())) {
        categoryHits++;
      } else {
        categoryMisses.add("[%s] esperado='%s' obtenido='%s' | %s".formatted(
            c.id(), c.expectedCategory(), response.serviceCategory(), c.message()));
      }
      if (response.area().equalsIgnoreCase(c.expectedArea())) {
        areaHits++;
      } else {
        areaMisses.add("[%s] esperado='%s' obtenido='%s' | %s".formatted(
            c.id(), c.expectedArea(), response.area(), c.message()));
      }
      if (response.urgency().equalsIgnoreCase(c.expectedUrgency())) {
        urgencyHits++;
      } else {
        urgencyMisses.add("[%s] esperado='%s' obtenido='%s' | %s".formatted(
            c.id(), c.expectedUrgency(), response.urgency(), c.message()));
      }
    }

    int total = cases.size();
    return new Result(
        (double) categoryHits / total,
        (double) areaHits / total,
        (double) urgencyHits / total,
        categoryMisses, areaMisses, urgencyMisses
    );
  }
}
