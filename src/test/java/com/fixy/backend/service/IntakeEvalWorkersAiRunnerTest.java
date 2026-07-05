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
 * Runner MANUAL del golden set contra Cloudflare Workers AI real (fixy.agent.provider=workersai,
 * la config actual de producción). NO corre en CI: se activa solo si CF_API_TOKEN y CF_ACCOUNT_ID
 * están presentes en el ambiente.
 *
 * CÓMO CORRERLO (con credenciales reales de Cloudflare):
 *   CF_ACCOUNT_ID=... CF_API_TOKEN=... \
 *     [CF_MODEL=@cf/meta/llama-3.3-70b-instruct-fp8-fast] \
 *     mvn -o test -Dtest=IntakeEvalWorkersAiRunnerTest
 *
 * Modelo por defecto: @cf/meta/llama-3.3-70b-instruct-fp8-fast (el mismo validado hoy en prod
 * para LeadAgentService, que resuelve los 3 bugs conocidos del heurístico documentados en
 * IntakeEvalTest: plomería con substring de zona, Lomas/Colinas de Solymar matcheados como
 * "Solymar" a secas, y negación de urgencia "no es urgente" clasificada como alta).
 * NO usar @cf/openai/gpt-oss-* -- no cumple el contrato response_format json_schema (response
 * queda null).
 *
 * El test compara accuracy del LLM real contra la línea base heurística ya medida en
 * IntakeEvalTest e imprime ambos reportes lado a lado. Igual que el runner de OpenAI, el único
 * assert es "no debería ser peor que el heurístico" (smoke check de regresión grosera); el resto
 * es reporte para que un humano lo lea.
 */
class IntakeEvalWorkersAiRunnerTest {

  @Test
  @EnabledIfEnvironmentVariable(named = "CF_API_TOKEN", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "CF_ACCOUNT_ID", matches = ".+")
  void compareWorkersAiAccuracyAgainstHeuristicBaseline() throws IOException {
    String accountId = System.getenv("CF_ACCOUNT_ID");
    String apiToken = System.getenv("CF_API_TOKEN");
    String model = System.getenv().getOrDefault("CF_MODEL", "@cf/meta/llama-3.3-70b-instruct-fp8-fast");

    AgentService workersAiService = new AgentService(
        new ObjectMapper(), "", "gpt-5-mini", "workersai", accountId, apiToken, model);
    AgentService heuristicService = new AgentService(new ObjectMapper(), "", "gpt-5-mini");

    List<IntakeEvalTest.GoldenCase> cases = IntakeEvalTest.loadGoldenSet();

    Result workersAiResult = run(workersAiService, cases);
    Result heuristicResult = run(heuristicService, cases);

    System.out.println();
    System.out.println("=== EVAL WORKERSAI REAL vs HEURISTICO (modelo=" + model + ", " + cases.size() + " casos) ===");
    System.out.printf("WorkersAI  -> categoria: %.1f%% | zona: %.1f%% | urgencia: %.1f%%%n",
        workersAiResult.categoryAccuracy() * 100, workersAiResult.areaAccuracy() * 100, workersAiResult.urgencyAccuracy() * 100);
    System.out.printf("Heuristico -> categoria: %.1f%% | zona: %.1f%% | urgencia: %.1f%%%n",
        heuristicResult.categoryAccuracy() * 100, heuristicResult.areaAccuracy() * 100, heuristicResult.urgencyAccuracy() * 100);
    if (!workersAiResult.categoryMisses().isEmpty()) {
      System.out.println("--- WorkersAI fallos de categoria ---");
      workersAiResult.categoryMisses().forEach(System.out::println);
    }
    if (!workersAiResult.areaMisses().isEmpty()) {
      System.out.println("--- WorkersAI fallos de zona ---");
      workersAiResult.areaMisses().forEach(System.out::println);
    }
    if (!workersAiResult.urgencyMisses().isEmpty()) {
      System.out.println("--- WorkersAI fallos de urgencia ---");
      workersAiResult.urgencyMisses().forEach(System.out::println);
    }
    System.out.println("=== fin del reporte ===");
    System.out.println();

    // Smoke check minimo: WorkersAI no deberia degradar por debajo de la heuristica en
    // categoria. Si esto falla, algo esta mal (ej. el modelo no cumple el contrato json_schema
    // y todo cae a null/fallback silencioso) -- no es un check de calidad fino, es una alarma.
    assertTrue(workersAiResult.categoryAccuracy() >= heuristicResult.categoryAccuracy() - 0.05,
        "WorkersAI tuvo peor accuracy de categoria que el heuristico -- revisar logs por posible fallback silencioso o modelo incompatible con json_schema");
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
