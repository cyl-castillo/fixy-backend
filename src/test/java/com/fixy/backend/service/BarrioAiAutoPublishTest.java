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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.model.BarrioReport;
import com.fixy.backend.repository.BarrioReportRepository;
import com.fixy.backend.repository.BarrioSettingRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

/** IA revisora de Avisá, Fase 2: auto-publicación, interruptor, tope, dedupe, confirm y unpublish. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.uploads.dir=target/barrio-ai-auto-test-uploads",
    "fixy.barrio.ai-review.scheduler-initial-delay-ms=3600000",
    "fixy.barrio.ai-review.scheduler-fixed-delay-ms=3600000",
    "fixy.barrio.ai-review.auto-approve-daily-cap=3",
    "fixy.openai.api-key=sk-test-falsa",
    "fixy.openai.model=gpt-5-mini"
})
class BarrioAiAutoPublishTest {

  private static final HttpServer FAKE;
  private static final AtomicReference<String> NEXT_JSON = new AtomicReference<>();

  static {
    try {
      FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      FAKE.createContext("/v1/responses", ex -> {
        ex.getRequestBody().readAllBytes();
        String body = "{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"summary\":[]},"
            + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":"
            + new ObjectMapper().writeValueAsString(NEXT_JSON.get()) + "}]}]}";
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
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
  @Autowired private BarrioSettingRepository settingsRepo;
  @Autowired private BarrioAiReviewScheduler scheduler;
  @Autowired private ObjectMapper mapper;
  @MockitoBean private TelegramNotifyService telegram;

  private static String opinion(double confidence) {
    return "{\"shows_public_issue\":true,\"matches_category\":true,\"suggested_category\":\"bache\","
        + "\"has_people_faces\":false,\"has_license_plates\":false,\"offensive_or_inappropriate\":false,"
        + "\"image_quality_ok\":true,\"confidence\":" + confidence + ",\"reason\":\"Pozo en la calzada\"}";
  }

  @BeforeEach
  void preparar() {
    reset(telegram);
    NEXT_JSON.set(opinion(0.95));
    // Pendientes ajenos fuera del lote de 5 y auto-publicados de otros tests fuera del tope de hoy.
    reports.findByStatusOrderByCreatedAtAsc(BarrioReport.PENDING_REVIEW).forEach(r -> {
      r.setAiVerdict("SIN_IA");
      reports.save(r);
    });
    reports.findByAutoPublishedAtIsNotNull().forEach(r -> {
      r.setAutoPublishedAt(OffsetDateTime.now().minusDays(3));
      reports.save(r);
    });
    setSwitch(true);
  }

  @AfterEach
  void limpiar() {
    settingsRepo.deleteAll();
  }

  private void setSwitch(boolean on) {
    try {
      mvc.perform(put("/api/ops/barrio/ai-settings").with(httpBasic("test-ops", "test-pass"))
          .contentType("application/json").content("{\"autoPublish\":" + on + "}")).andReturn();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private long crear(double lat, double lng, String description) throws Exception {
    var req = multipart("/api/public/barrio/reports")
        .file(new MockMultipartFile("photo", "f.jpg", "image/jpeg", BarrioTestImages.jpeg(320, 240)))
        .param("category", "bache").param("lat", String.valueOf(lat)).param("lng", String.valueOf(lng));
    if (description != null) req.param("description", description);
    var res = mvc.perform(req).andReturn();
    return mapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
  }

  private JsonNode ops(String path) throws Exception {
    return mapper.readTree(mvc.perform(get(path).with(httpBasic("test-ops", "test-pass")))
        .andReturn().getResponse().getContentAsString());
  }

  private int opsPost(String path, String body) throws Exception {
    var req = post(path).with(httpBasic("test-ops", "test-pass"));
    if (body != null) req.contentType("application/json").content(body);
    return mvc.perform(req).andReturn().getResponse().getStatus();
  }

  private boolean listed(JsonNode arr, long id) {
    for (JsonNode n : arr) if (n.get("id").asLong() == id) return true;
    return false;
  }

  private boolean publicListed(long id) throws Exception {
    String body = mvc.perform(get("/api/public/barrio/reports?status=all")).andReturn().getResponse().getContentAsString();
    return listed(mapper.readTree(body), id);
  }

  @Test
  void interruptorApagadoNoPublicaAunqueLaIaDiga99() throws Exception {
    setSwitch(false);
    NEXT_JSON.set(opinion(0.99));
    long id = crear(-34.8601, -55.9150, null);
    scheduler.runOnce();
    BarrioReport r = reports.findById(id).orElseThrow();
    assertThat(r.getStatus()).isEqualTo("PENDING_REVIEW");
    assertThat(r.getAiVerdict()).isEqualTo("APROBAR");
    assertThat(r.getAutoPublishedAt()).isNull();
    verify(telegram, timeout(2000)).notifyBarrioReport(eq(id), anyString(), anyString(), any(), contains("IA: aprobar (99 %)"));
    verify(telegram, never()).notifyBarrioAutoPublished(eq(id), any(), any(), any(), anyLong(), any());
  }

  @Test
  void publicaSolaYDespublicarLoSacaDeLoPublico() throws Exception {
    JsonNode before = ops("/api/ops/barrio/ai-agreement");
    long id = crear(-34.8611, -55.9150, null);
    scheduler.runOnce();

    BarrioReport r = reports.findById(id).orElseThrow();
    assertThat(r.getStatus()).isEqualTo("PUBLISHED");
    assertThat(r.getAutoPublishedAt()).isNotNull();
    assertThat(r.getHumanDecision()).isNull();
    assertThat(publicListed(id)).isTrue();
    verify(telegram, timeout(2000)).notifyBarrioAutoPublished(eq(id), eq("Bache"), anyString(), any(), eq(95L), eq(null));
    verify(telegram, never()).notifyBarrioReport(eq(id), any(), any(), any(), any());

    JsonNode auto = ops("/api/ops/barrio/auto-published");
    assertThat(listed(auto, id)).isTrue();
    JsonNode mine = null;
    for (JsonNode n : auto) if (n.get("id").asLong() == id) mine = n;
    assertThat(mine.get("status").asText()).isEqualTo("PUBLISHED");
    assertThat(mine.get("ai").get("verdict").asText()).isEqualTo("APROBAR");
    assertThat(mine.get("autoPublishedAt").isNull()).isFalse();

    JsonNode mid = ops("/api/ops/barrio/ai-agreement");
    assertThat(mid.get("autoPublished").asLong()).isEqualTo(before.get("autoPublished").asLong() + 1);

    assertThat(opsPost("/api/ops/barrio/reports/" + id + "/unpublish", "{\"reason\":\"no es un bache\"}")).isEqualTo(200);
    r = reports.findById(id).orElseThrow();
    assertThat(r.getStatus()).isEqualTo("REJECTED");
    assertThat(r.getRejectReason()).isEqualTo("no es un bache");
    assertThat(r.getHumanDecision()).isEqualTo("REJECTED");
    assertThat(publicListed(id)).isFalse();
    mvc.perform(get("/api/public/barrio/reports/{id}", id)).andExpect(status().isNotFound());
    assertThat(listed(ops("/api/ops/barrio/auto-published"), id)).isFalse();

    JsonNode after = ops("/api/ops/barrio/ai-agreement");
    assertThat(after.get("autoUnpublished").asLong()).isEqualTo(before.get("autoUnpublished").asLong() + 1);
    assertThat(after.get("aiApproveHumanRejected").asLong()).isEqualTo(before.get("aiApproveHumanRejected").asLong() + 1);
    // un segundo unpublish ya no aplica
    assertThat(opsPost("/api/ops/barrio/reports/" + id + "/unpublish", null)).isEqualTo(409);
  }

  @Test
  void bajoElUmbralOSmokeQuedaPendiente() throws Exception {
    NEXT_JSON.set(opinion(0.85));
    long bajo = crear(-34.8621, -55.9150, null);
    scheduler.runOnce();
    assertThat(reports.findById(bajo).orElseThrow().getStatus()).isEqualTo("PENDING_REVIEW");

    NEXT_JSON.set(opinion(0.99));
    long smoke = crear(-34.8631, -55.9150, "prueba [smoke]");
    scheduler.runOnce();
    BarrioReport r = reports.findById(smoke).orElseThrow();
    assertThat(r.getStatus()).isEqualTo("PENDING_REVIEW");
    assertThat(r.getAiVerdict()).isEqualTo("APROBAR");
  }

  @Test
  void topeDiarioDejaElResto() throws Exception {
    long a = crear(-34.8641, -55.9150, null);
    long b = crear(-34.8651, -55.9150, null);
    long c = crear(-34.8661, -55.9150, null);
    scheduler.runOnce();
    for (long id : new long[] {a, b, c}) {
      assertThat(reports.findById(id).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
    }
    assertThat(ops("/api/ops/barrio/ai-settings").get("publishedToday").asLong()).isEqualTo(3);

    long d = crear(-34.8671, -55.9150, null);
    scheduler.runOnce();
    assertThat(reports.findById(d).orElseThrow().getStatus()).isEqualTo("PENDING_REVIEW");
    verify(telegram, timeout(2000)).notifyBarrioReport(eq(d), anyString(), anyString(), any(),
        contains("(tope diario de la IA alcanzado)"));
  }

  @Test
  void conDedupeQuedaMergedYUnpublishRestaLosSupporters() throws Exception {
    setSwitch(false);
    long target = crear(-34.8701, -55.9150, null);
    scheduler.runOnce();
    assertThat(opsPost("/api/ops/barrio/reports/" + target + "/approve", null)).isEqualTo(200);
    setSwitch(true);

    long dup = crear(-34.8702, -55.9150, null); // ~11 m
    scheduler.runOnce();
    BarrioReport d = reports.findById(dup).orElseThrow();
    assertThat(d.getStatus()).isEqualTo("MERGED");
    assertThat(d.getMergedIntoId()).isEqualTo(target);
    assertThat(d.getAutoPublishedAt()).isNotNull();
    assertThat(d.getHumanDecision()).isNull();
    assertThat(reports.findById(target).orElseThrow().getSupporters()).isEqualTo(2);
    verify(telegram, timeout(2000)).notifyBarrioAutoPublished(eq(dup), anyString(), anyString(), any(), eq(95L), eq(target));

    JsonNode mine = null;
    for (JsonNode n : ops("/api/ops/barrio/auto-published")) if (n.get("id").asLong() == dup) mine = n;
    assertThat(mine.get("status").asText()).isEqualTo("MERGED");
    assertThat(mine.get("mergedIntoId").asLong()).isEqualTo(target);

    assertThat(opsPost("/api/ops/barrio/reports/" + dup + "/unpublish", null)).isEqualTo(200);
    assertThat(reports.findById(dup).orElseThrow().getStatus()).isEqualTo("REJECTED");
    assertThat(reports.findById(target).orElseThrow().getSupporters()).isEqualTo(1);
    assertThat(reports.findById(target).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
  }

  @Test
  void unpublishRechazaResueltoYPendiente() throws Exception {
    setSwitch(false);
    long pendiente = crear(-34.8711, -55.9150, null);
    assertThat(opsPost("/api/ops/barrio/reports/" + pendiente + "/unpublish", null)).isEqualTo(409);

    long resuelto = crear(-34.8721, -55.9150, null);
    BarrioReport r = reports.findById(resuelto).orElseThrow();
    r.setStatus(BarrioReport.RESOLVED);
    reports.save(r);
    assertThat(opsPost("/api/ops/barrio/reports/" + resuelto + "/unpublish", null)).isEqualTo(409);
    assertThat(reports.findById(resuelto).orElseThrow().getStatus()).isEqualTo("RESOLVED");
    assertThat(opsPost("/api/ops/barrio/reports/9999999/unpublish", null)).isEqualTo(404);
  }

  @Test
  void confirmSoloParaAutoPublicadosSinDecidir() throws Exception {
    long id = crear(-34.8731, -55.9150, null);
    scheduler.runOnce();
    assertThat(opsPost("/api/ops/barrio/reports/" + id + "/confirm", null)).isEqualTo(200);
    BarrioReport r = reports.findById(id).orElseThrow();
    assertThat(r.getHumanDecision()).isEqualTo("APPROVED");
    assertThat(r.getHumanDecidedAt()).isNotNull();
    assertThat(r.getStatus()).isEqualTo("PUBLISHED");
    assertThat(listed(ops("/api/ops/barrio/auto-published"), id)).isFalse();
    assertThat(opsPost("/api/ops/barrio/reports/" + id + "/confirm", null)).isEqualTo(409);

    // aprobado a mano (sin auto_published_at): 409
    setSwitch(false);
    long manual = crear(-34.8741, -55.9150, null);
    scheduler.runOnce();
    assertThat(opsPost("/api/ops/barrio/reports/" + manual + "/approve", null)).isEqualTo(200);
    assertThat(opsPost("/api/ops/barrio/reports/" + manual + "/confirm", null)).isEqualTo(409);
  }

  @Test
  void confirmDeMergedQuedaMerged() throws Exception {
    setSwitch(false);
    long target = crear(-34.8751, -55.9150, null);
    scheduler.runOnce();
    opsPost("/api/ops/barrio/reports/" + target + "/approve", null);
    setSwitch(true);
    long dup = crear(-34.8752, -55.9150, null);
    scheduler.runOnce();
    assertThat(opsPost("/api/ops/barrio/reports/" + dup + "/confirm", null)).isEqualTo(200);
    assertThat(reports.findById(dup).orElseThrow().getHumanDecision()).isEqualTo("MERGED");
    assertThat(ops("/api/ops/barrio/ai-agreement").get("autoConfirmed").asLong()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void ajustesLaFilaGanaSobreLaProperty() throws Exception {
    settingsRepo.deleteAll();
    JsonNode s = ops("/api/ops/barrio/ai-settings");
    assertThat(s.get("autoPublish").asBoolean()).isFalse(); // property default
    assertThat(s.get("minConfidence").asDouble()).isEqualTo(0.90);
    assertThat(s.get("dailyCap").asInt()).isEqualTo(3);

    var res = mvc.perform(put("/api/ops/barrio/ai-settings").with(httpBasic("test-ops", "test-pass"))
        .contentType("application/json").content("{\"autoPublish\":true}")).andReturn();
    assertThat(mapper.readTree(res.getResponse().getContentAsString()).get("autoPublish").asBoolean()).isTrue();
    assertThat(ops("/api/ops/barrio/ai-settings").get("autoPublish").asBoolean()).isTrue();
    setSwitch(false);
    assertThat(ops("/api/ops/barrio/ai-settings").get("autoPublish").asBoolean()).isFalse();

    mvc.perform(put("/api/ops/barrio/ai-settings").with(httpBasic("test-ops", "test-pass"))
        .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/ops/barrio/ai-settings")).andExpect(status().isUnauthorized());
  }

  @Test
  void laApiPublicaNoExponeCamposDeIa() throws Exception {
    long id = crear(-34.8761, -55.9150, null);
    scheduler.runOnce();
    String pub = mvc.perform(get("/api/public/barrio/reports/{id}", id)).andReturn().getResponse().getContentAsString();
    assertThat(pub).doesNotContain("ai").doesNotContain("auto").doesNotContain("humanDecision");
  }
}
