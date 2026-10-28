package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.core.cases.AgentTurn;
import com.fixy.backend.core.cases.AgentTurnRepository;
import com.fixy.backend.model.BarrioReport;
import com.fixy.backend.repository.BarrioReportRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * IA revisora de Avisá (Fase 1) de punta a punta contra un OpenAI FALSO local: alta por HTTP →
 * scheduler (runOnce) → payload con imagen → veredicto → ops/pending → coincidencia. El scheduler
 * real queda apagado por delay enorme; se dispara a mano.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.uploads.dir=target/barrio-ai-test-uploads",
    "fixy.barrio.ai-review.scheduler-initial-delay-ms=3600000",
    "fixy.barrio.ai-review.scheduler-fixed-delay-ms=3600000",
    "fixy.openai.api-key=sk-test-falsa",
    "fixy.openai.model=gpt-5-mini"
})
class BarrioAiReviewTest {

  private static final HttpServer FAKE;
  private static final List<String> BODIES = new CopyOnWriteArrayList<>();
  private static final AtomicReference<String> NEXT_JSON = new AtomicReference<>();
  private static final AtomicReference<Integer> NEXT_STATUS = new AtomicReference<>(200);

  static {
    try {
      FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      FAKE.createContext("/v1/responses", ex -> {
        BODIES.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        int status = NEXT_STATUS.get();
        // Forma real de un modelo razonador: output[0] = reasoning, el message va después.
        String body = status != 200 ? "{\"error\":\"boom\"}"
            : "{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"summary\":[]},"
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":"
                + new ObjectMapper().writeValueAsString(NEXT_JSON.get()) + "}]}]}";
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
      });
      FAKE.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("fixy.openai.base-url", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort() + "/v1");
  }

  @AfterAll
  static void stop() {
    FAKE.stop(0);
  }

  @Autowired private MockMvc mvc;
  @Autowired private BarrioReportRepository reports;
  @Autowired private BarrioAiReviewScheduler scheduler;
  @Autowired private AgentTurnRepository turns;
  @Autowired private ObjectMapper mapper;
  @MockitoBean private TelegramNotifyService telegram;

  private static String opinion(boolean faces, double confidence, String suggested) {
    return "{\"shows_public_issue\":true,\"matches_category\":true,\"suggested_category\":\"" + suggested
        + "\",\"has_people_faces\":" + faces + ",\"has_license_plates\":false,"
        + "\"offensive_or_inappropriate\":false,\"image_quality_ok\":true,\"confidence\":" + confidence
        + ",\"reason\":\"Pozo en la calzada\"}";
  }

  @BeforeEach
  void limpiarPendientesAjenos() {
    BODIES.clear();
    NEXT_STATUS.set(200);
    // Los pendientes de otros tests del contexto no deben consumir el lote de 5.
    reports.findByStatusOrderByCreatedAtAsc(BarrioReport.PENDING_REVIEW).forEach(r -> {
      r.setAiVerdict("SIN_IA");
      reports.save(r);
    });
    reset(telegram);
  }

  private long crear(String category, double lat) throws Exception {
    var res = mvc.perform(multipart("/api/public/barrio/reports")
            .file(new MockMultipartFile("photo", "f.jpg", "image/jpeg", BarrioTestImages.jpeg(320, 240)))
            .param("category", category).param("lat", String.valueOf(lat)).param("lng", "-55.9331"))
        .andReturn();
    return mapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
  }

  private JsonNode pendingOf(long id) throws Exception {
    String body = mvc.perform(get("/api/ops/barrio/pending").with(httpBasic("test-ops", "test-pass")))
        .andReturn().getResponse().getContentAsString();
    for (JsonNode n : mapper.readTree(body).get("reports")) {
      if (n.get("id").asLong() == id) return n;
    }
    throw new AssertionError("reclamo " + id + " no está en pending");
  }

  private JsonNode agreement() throws Exception {
    return mapper.readTree(mvc.perform(get("/api/ops/barrio/ai-agreement").with(httpBasic("test-ops", "test-pass")))
        .andReturn().getResponse().getContentAsString());
  }

  @Test
  void flujoCompletoAprobarYCoincidencia() throws Exception {
    JsonNode before = agreement();
    NEXT_JSON.set(opinion(false, 0.92, "bache"));
    long id = crear("bache", -34.8101);

    // El vecino no espera y Telegram todavía no avisó: sale después de la revisión.
    verify(telegram, never()).notifyBarrioReport(eq(id), anyString(), anyString(), any(), any());
    assertThat(pendingOf(id).get("ai").isNull()).isTrue();

    assertThat(scheduler.runOnce()).isEqualTo(1);

    JsonNode body = mapper.readTree(BODIES.get(0));
    assertThat(body.get("model").asText()).isEqualTo("gpt-5-mini");
    JsonNode content = body.get("input").get(0).get("content");
    assertThat(body.get("reasoning").get("effort").asText()).isEqualTo("low");
    assertThat(content.get(0).get("type").asText()).isEqualTo("input_text");
    assertThat(content.get(1).get("type").asText()).isEqualTo("input_image");
    assertThat(content.get(1).get("image_url").asText()).startsWith("data:image/jpeg;base64,/9j/");
    JsonNode fmt = body.get("text").get("format");
    assertThat(fmt.get("type").asText()).isEqualTo("json_schema");
    assertThat(fmt.get("strict").asBoolean()).isTrue();
    assertThat(fmt.get("schema").get("required")).hasSize(9);
    assertThat(fmt.get("schema").get("additionalProperties").asBoolean()).isFalse();

    JsonNode ai = pendingOf(id).get("ai");
    assertThat(ai.get("verdict").asText()).isEqualTo("APROBAR");
    assertThat(ai.get("confidence").asDouble()).isEqualTo(0.92);
    assertThat(ai.get("reasons")).isEmpty();
    assertThat(ai.get("summary").asText()).isEqualTo("Pozo en la calzada");
    verify(telegram, timeout(2000)).notifyBarrioReport(eq(id), anyString(), anyString(), any(), contains("IA: aprobar (92 %)"));

    mvc.perform(post("/api/ops/barrio/reports/{id}/approve", id).with(httpBasic("test-ops", "test-pass")))
        .andReturn();
    JsonNode after = agreement();
    assertThat(after.get("reviewed").asLong()).isEqualTo(before.get("reviewed").asLong() + 1);
    assertThat(after.get("decided").asLong()).isEqualTo(before.get("decided").asLong() + 1);
    assertThat(after.get("agree").asLong()).isEqualTo(before.get("agree").asLong() + 1);
    assertThat(after.get("aiApproveHumanApproved").asLong()).isEqualTo(before.get("aiApproveHumanApproved").asLong() + 1);

    // Ya PUBLISHED: no se vuelve a revisar y la API pública no filtra nada de la IA.
    BODIES.clear();
    assertThat(scheduler.runOnce()).isZero();
    String pub = mvc.perform(get("/api/public/barrio/reports/{id}", id)).andReturn().getResponse().getContentAsString();
    assertThat(pub).doesNotContain("ai").doesNotContain("humanDecision");
  }

  @Test
  void dudosoRechazadoPorElHumanoCuentaComoAcuerdo() throws Exception {
    JsonNode before = agreement();
    NEXT_JSON.set(opinion(true, 0.55, "poda"));
    long id = crear("bache", -34.8202);
    scheduler.runOnce();

    JsonNode ai = pendingOf(id).get("ai");
    assertThat(ai.get("verdict").asText()).isEqualTo("DUDOSO");
    assertThat(ai.get("reasons")).extracting(JsonNode::asText).containsExactly("personas", "baja_confianza");
    assertThat(ai.get("suggestedCategory").asText()).isEqualTo("poda");
    verify(telegram, timeout(2000)).notifyBarrioReport(eq(id), anyString(), anyString(), any(),
        contains("IA: dudoso — se ven caras, baja confianza. La IA cree que es: Restos de poda"));

    mvc.perform(post("/api/ops/barrio/reports/{id}/reject", id).with(httpBasic("test-ops", "test-pass"))
        .contentType("application/json").content("{}")).andReturn();
    JsonNode after = agreement();
    assertThat(after.get("aiDoubtHumanRejected").asLong()).isEqualTo(before.get("aiDoubtHumanRejected").asLong() + 1);
    assertThat(after.get("agree").asLong()).isEqualTo(before.get("agree").asLong() + 1);
  }

  @Test
  void erroresSeReintentanYAlTercerIntentoAvisaIgual() throws Exception {
    NEXT_STATUS.set(500);
    long id = crear("luminaria", -34.8303);
    scheduler.runOnce();
    scheduler.runOnce();
    verify(telegram, never()).notifyBarrioReport(eq(id), anyString(), anyString(), any(), any());
    assertThat(pendingOf(id).get("ai").isNull()).isTrue(); // "revisando" mientras quedan intentos
    scheduler.runOnce();
    assertThat(pendingOf(id).get("ai").get("verdict").asText()).isEqualTo("ERROR");
    verify(telegram, timeout(2000)).notifyBarrioReport(eq(id), anyString(), anyString(), any(), contains("IA: no pudo revisar"));
    assertThat(reports.findById(id).orElseThrow().getAiAttempts()).isEqualTo(3);
    assertThat(scheduler.runOnce()).isZero(); // intentos agotados
  }

  @Test
  void respuestaInvalidaEsError() throws Exception {
    NEXT_JSON.set("{\"shows_public_issue\":true}");
    long id = crear("bache", -34.8404);
    scheduler.runOnce();
    assertThat(reports.findById(id).orElseThrow().getAiVerdict()).isEqualTo("ERROR");
  }

  @Test
  void agentTurnsRegistraLaLlamadaSinBase64() throws Exception {
    NEXT_JSON.set(opinion(false, 0.95, "bache"));
    long id = crear("bache", -34.8505);
    scheduler.runOnce();
    List<AgentTurn> rows = turns.findAll().stream().filter(t -> "BARRIO_REVIEW".equals(t.getKind())).toList();
    assertThat(rows).isNotEmpty();
    for (AgentTurn t : rows) {
      assertThat(t.getLeadId()).isNull();
      assertThat(t.getUserContent()).doesNotContain("base64").doesNotContain("/9j/");
      assertThat(t.getRawOutput() == null ? "" : t.getRawOutput()).doesNotContain("base64");
    }
    assertThat(rows).anyMatch(t -> t.getUserContent().contains("[foto barrio/") && t.getUserContent().contains("#" + id)
        && "APROBAR".equals(t.getOutcome()));
  }
}
