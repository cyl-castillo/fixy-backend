package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.fixy.backend.model.BarrioClosureClaim;
import com.fixy.backend.repository.BarrioClosureClaimRepository;
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
 * Cierre verificable C1 de punta a punta contra un OpenAI FALSO local: cierre por HTTP con ubicación →
 * scheduler de cierres (runOnce) → payload con 2 input_image → pruebas FUERTE/DEBIL → ops/pending →
 * coincidencia. Los schedulers reales quedan apagados por delay enorme.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.uploads.dir=target/barrio-closure-test-uploads",
    "fixy.barrio.ai-review.scheduler-initial-delay-ms=3600000",
    "fixy.barrio.ai-review.scheduler-fixed-delay-ms=3600000",
    "fixy.openai.api-key=sk-test-falsa",
    "fixy.openai.model=gpt-5-mini"
})
class BarrioClosureReviewTest {

  private static final HttpServer FAKE;
  private static final List<String> BODIES = new CopyOnWriteArrayList<>();
  private static final AtomicReference<String> NEXT_JSON = new AtomicReference<>();
  private static final AtomicReference<Integer> NEXT_STATUS = new AtomicReference<>(200);
  private static final double LNG = -55.9001;

  static {
    try {
      FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      FAKE.createContext("/v1/responses", ex -> {
        BODIES.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        int status = NEXT_STATUS.get();
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
  @Autowired private BarrioClosureClaimRepository claims;
  @Autowired private BarrioClosureReviewScheduler scheduler;
  @Autowired private AgentTurnRepository turns;
  @Autowired private ObjectMapper mapper;
  @MockitoBean private TelegramNotifyService telegram;

  private static String opinion(boolean samePlace, boolean issueGone, boolean reused, double confidence) {
    return "{\"same_place\":" + samePlace + ",\"issue_gone\":" + issueGone + ",\"looks_like_reused_photo\":" + reused
        + ",\"has_people_faces\":false,\"has_license_plates\":false,\"image_quality_ok\":true,"
        + "\"confidence\":" + confidence + ",\"reason\":\"Pozo tapado con asfalto\"}";
  }

  @BeforeEach
  void limpiarPendientesAjenos() {
    BODIES.clear();
    NEXT_STATUS.set(200);
    // Cierres pendientes de otros tests del contexto no deben consumir el lote de 5.
    claims.findByStatusOrderByCreatedAtAsc(BarrioClosureClaim.PENDING_REVIEW).forEach(c -> {
      c.setAiVerdict("SIN_IA");
      claims.save(c);
    });
    reports.findByStatusOrderByCreatedAtAsc("PENDING_REVIEW").forEach(r -> {
      r.setAiVerdict("SIN_IA");
      reports.save(r);
    });
    reset(telegram);
  }

  private long publishedReport(double lat, byte[] photo) throws Exception {
    var res = mvc.perform(multipart("/api/public/barrio/reports")
            .file(new MockMultipartFile("photo", "f.jpg", "image/jpeg", photo))
            .param("category", "bache").param("lat", String.valueOf(lat)).param("lng", String.valueOf(LNG)))
        .andReturn();
    long id = mapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    mvc.perform(post("/api/ops/barrio/reports/{id}/approve", id).with(httpBasic("test-ops", "test-pass"))).andReturn();
    reset(telegram);
    return id;
  }

  private void closure(long reportId, byte[] photo, Double lat, Double lng, Double accuracy) throws Exception {
    var req = multipart("/api/public/barrio/reports/{id}/closure", reportId)
        .file(new MockMultipartFile("photo", "c.jpg", "image/jpeg", photo));
    if (lat != null) req.param("lat", String.valueOf(lat));
    if (lng != null) req.param("lng", String.valueOf(lng));
    if (accuracy != null) req.param("accuracy", String.valueOf(accuracy));
    assertThat(mvc.perform(req).andReturn().getResponse().getStatus()).isEqualTo(201);
  }

  private JsonNode pendingClosureOf(long reportId) throws Exception {
    String body = mvc.perform(get("/api/ops/barrio/pending").with(httpBasic("test-ops", "test-pass")))
        .andReturn().getResponse().getContentAsString();
    for (JsonNode n : mapper.readTree(body).get("closures")) {
      if (n.get("reportId").asLong() == reportId) return n;
    }
    throw new AssertionError("cierre del reclamo " + reportId + " no está en pending");
  }

  private JsonNode agreement() throws Exception {
    return mapper.readTree(mvc.perform(get("/api/ops/barrio/ai-agreement").with(httpBasic("test-ops", "test-pass")))
        .andReturn().getResponse().getContentAsString());
  }

  @Test
  void cierreCercaConFotoNuevaEsFuerteYLaIARecibeDosImagenes() throws Exception {
    JsonNode before = agreement().get("closures");
    long id = publishedReport(-34.7611, BarrioTestImages.textured(11));
    NEXT_JSON.set(opinion(true, true, false, 0.91));
    closure(id, BarrioTestImages.textured(12), -34.7611, LNG, 12.0);

    // El vecino no espera y Telegram todavía no avisó: sale después de la revisión.
    verify(telegram, never()).notifyBarrioClosure(eq(id), anyString(), anyString(), any(), anyString());
    JsonNode pend = pendingClosureOf(id);
    assertThat(pend.get("evidence").get("verdict").asText()).isEqualTo("PENDIENTE");
    assertThat(pend.get("evidence").get("ai").isNull()).isTrue();

    assertThat(scheduler.runOnce()).isGreaterThanOrEqualTo(1);

    JsonNode body = mapper.readTree(BODIES.get(0));
    JsonNode content = body.get("input").get(0).get("content");
    assertThat(content).hasSize(3);
    assertThat(content.get(0).get("type").asText()).isEqualTo("input_text");
    assertThat(content.get(1).get("type").asText()).isEqualTo("input_image");
    assertThat(content.get(2).get("type").asText()).isEqualTo("input_image");
    assertThat(content.get(1).get("image_url").asText()).startsWith("data:image/jpeg;base64,/9j/");
    assertThat(content.get(1).get("image_url").asText()).isNotEqualTo(content.get(2).get("image_url").asText());
    JsonNode schema = body.get("text").get("format").get("schema");
    assertThat(schema.get("required")).hasSize(8);
    assertThat(schema.get("additionalProperties").asBoolean()).isFalse();

    JsonNode ev = pendingClosureOf(id).get("evidence");
    assertThat(ev.get("verdict").asText()).isEqualTo("FUERTE");
    assertThat(ev.get("reasons").toString()).doesNotContain("lejos").doesNotContain("misma_foto");
    assertThat(ev.get("distanceM").asDouble()).isLessThan(1.0);
    assertThat(ev.get("accuracyM").asDouble()).isEqualTo(12.0);
    assertThat(ev.get("dhashMinDistance").asInt()).isGreaterThan(8);
    assertThat(ev.get("ai").get("verdict").asText()).isEqualTo("REVISADO");
    assertThat(ev.get("ai").get("samePlace").asBoolean()).isTrue();
    assertThat(ev.get("ai").get("issueGone").asBoolean()).isTrue();
    assertThat(ev.get("ai").get("confidence").asDouble()).isEqualTo(0.91);
    assertThat(ev.get("ai").get("summary").asText()).isEqualTo("Pozo tapado con asfalto");
    assertThat(pendingClosureOf(id).get("beforePhotoUrl").asText()).contains("/uploads/barrio/");
    verify(telegram, timeout(2000)).notifyBarrioClosure(eq(id), anyString(), anyString(), any(),
        contains("Pruebas: FUERTES (IA 91 %: mismo lugar, ya no está)"));

    // Carlos aprueba: coincidencia FUERTE + aprobado.
    long claim = pendingClosureOf(id).get("id").asLong();
    mvc.perform(post("/api/ops/barrio/closures/{id}/approve", claim).with(httpBasic("test-ops", "test-pass"))).andReturn();
    JsonNode after = agreement().get("closures");
    assertThat(after.get("reviewed").asLong()).isEqualTo(before.get("reviewed").asLong() + 1);
    assertThat(after.get("decided").asLong()).isEqualTo(before.get("decided").asLong() + 1);
    assertThat(after.get("agree").asLong()).isEqualTo(before.get("agree").asLong() + 1);
    assertThat(after.get("strongApproved").asLong()).isEqualTo(before.get("strongApproved").asLong() + 1);
    assertThat(claims.findById(claim).orElseThrow().getHumanDecision()).isEqualTo("APPROVED");
  }

  @Test
  void laMismaFotoDelReclamoEsDebilAunqueLaIAOpineBien() throws Exception {
    JsonNode before = agreement().get("closures");
    byte[] photo = BarrioTestImages.textured(21);
    long id = publishedReport(-34.7622, photo);
    NEXT_JSON.set(opinion(true, true, false, 0.95));
    closure(id, photo, -34.7622, LNG, 8.0);
    scheduler.runOnce();
    JsonNode ev = pendingClosureOf(id).get("evidence");
    assertThat(ev.get("verdict").asText()).isEqualTo("DEBIL");
    assertThat(ev.get("reasons")).extracting(JsonNode::asText).containsExactly("sin_fecha", "misma_foto");
    assertThat(ev.get("dhashMinDistance").asInt()).isLessThanOrEqualTo(8);
    verify(telegram, timeout(2000)).notifyBarrioClosure(eq(id), anyString(), anyString(), any(),
        contains("Pruebas: débiles — parece la misma foto"));

    long claim = pendingClosureOf(id).get("id").asLong();
    mvc.perform(post("/api/ops/barrio/closures/{id}/reject", claim).with(httpBasic("test-ops", "test-pass"))).andReturn();
    JsonNode after = agreement().get("closures");
    assertThat(after.get("weakRejected").asLong()).isEqualTo(before.get("weakRejected").asLong() + 1);
    assertThat(after.get("agree").asLong()).isEqualTo(before.get("agree").asLong() + 1);
  }

  @Test
  void sinUbicacionEsDebil() throws Exception {
    long id = publishedReport(-34.7633, BarrioTestImages.textured(31));
    NEXT_JSON.set(opinion(true, true, false, 0.95));
    closure(id, BarrioTestImages.textured(32), null, null, null);
    scheduler.runOnce();
    JsonNode ev = pendingClosureOf(id).get("evidence");
    assertThat(ev.get("verdict").asText()).isEqualTo("DEBIL");
    assertThat(ev.get("reasons")).extracting(JsonNode::asText).contains("sin_ubicacion");
    assertThat(ev.get("distanceM").isNull()).isTrue();
  }

  @Test
  void lejosYFotoViejaPorExif() throws Exception {
    long id = publishedReport(-34.7644, BarrioTestImages.textured(41));
    NEXT_JSON.set(opinion(true, true, false, 0.95));
    byte[] vieja = BarrioTestImages.withExifDates(BarrioTestImages.textured(42), null, "2020:01:01 10:00:00");
    closure(id, vieja, -34.7604, LNG, 10.0); // ~445 m
    scheduler.runOnce();
    JsonNode ev = pendingClosureOf(id).get("evidence");
    assertThat(ev.get("verdict").asText()).isEqualTo("DEBIL");
    assertThat(ev.get("reasons")).extracting(JsonNode::asText).containsExactly("lejos", "foto_vieja");
    assertThat(ev.get("distanceM").asDouble()).isBetween(400.0, 500.0);
    assertThat(ev.get("photoTakenAt").isNull()).isFalse();
  }

  @Test
  void ubicacionInvalidaSeTrataComoSinUbicacion() throws Exception {
    long id = publishedReport(-34.7655, BarrioTestImages.textured(51));
    NEXT_JSON.set(opinion(true, true, false, 0.95));
    closure(id, BarrioTestImages.textured(52), 999.0, LNG, null);
    scheduler.runOnce();
    assertThat(pendingClosureOf(id).get("evidence").get("reasons")).extracting(JsonNode::asText).contains("sin_ubicacion");
  }

  @Test
  void erroresSeReintentanYAlTercerIntentoQuedaDebilSinIAYAvisa() throws Exception {
    long id = publishedReport(-34.7666, BarrioTestImages.textured(61));
    NEXT_STATUS.set(500);
    closure(id, BarrioTestImages.textured(62), -34.7666, LNG, 10.0);
    scheduler.runOnce();
    scheduler.runOnce();
    verify(telegram, never()).notifyBarrioClosure(eq(id), anyString(), anyString(), any(), anyString());
    JsonNode mid = pendingClosureOf(id).get("evidence");
    assertThat(mid.get("verdict").asText()).isEqualTo("PENDIENTE");
    assertThat(mid.get("ai").isNull()).isTrue();
    scheduler.runOnce();
    JsonNode ev = pendingClosureOf(id).get("evidence");
    assertThat(ev.get("verdict").asText()).isEqualTo("DEBIL");
    assertThat(ev.get("reasons")).extracting(JsonNode::asText).contains("sin_ia");
    assertThat(ev.get("ai").get("verdict").asText()).isEqualTo("ERROR");
    verify(telegram, timeout(2000)).notifyBarrioClosure(eq(id), anyString(), anyString(), any(), contains("sin IA"));
    long claim = ev.isNull() ? 0 : pendingClosureOf(id).get("id").asLong();
    assertThat(claims.findById(claim).orElseThrow().getAiAttempts()).isEqualTo(3);
    int calls = BODIES.size();
    scheduler.runOnce();
    assertThat(BODIES).hasSize(calls); // intentos agotados: no vuelve a llamar por este cierre
  }

  @Test
  void agentTurnsRegistraLaLlamadaSinBase64() throws Exception {
    long id = publishedReport(-34.7677, BarrioTestImages.textured(71));
    NEXT_JSON.set(opinion(true, true, false, 0.9));
    closure(id, BarrioTestImages.textured(72), -34.7677, LNG, 10.0);
    scheduler.runOnce();
    long claim = pendingClosureOf(id).get("id").asLong();
    List<AgentTurn> rows = turns.findAll().stream().filter(t -> "BARRIO_CLOSURE_REVIEW".equals(t.getKind())).toList();
    assertThat(rows).isNotEmpty();
    for (AgentTurn t : rows) {
      assertThat(t.getLeadId()).isNull();
      assertThat(t.getUserContent()).doesNotContain("base64").doesNotContain("/9j/");
      assertThat(t.getRawOutput() == null ? "" : t.getRawOutput()).doesNotContain("base64");
    }
    assertThat(rows).anyMatch(t -> t.getUserContent().contains("[antes barrio/")
        && t.getUserContent().contains("después barrio/")
        && t.getUserContent().contains("cierre #" + claim + " del reporte #" + id));
  }

  @Test
  void laApiPublicaNoExponeNadaNuevo() throws Exception {
    long id = publishedReport(-34.7688, BarrioTestImages.textured(81));
    NEXT_JSON.set(opinion(true, true, false, 0.9));
    var res = mvc.perform(multipart("/api/public/barrio/reports/{id}/closure", id)
        .file(new MockMultipartFile("photo", "c.jpg", "image/jpeg", BarrioTestImages.textured(82)))
        .param("lat", "-34.7688").param("lng", String.valueOf(LNG)).param("accuracy", "5")).andReturn();
    assertThat(res.getResponse().getContentAsString()).isEqualTo("{\"status\":\"PENDING_REVIEW\"}");
    scheduler.runOnce();
    String detail = mvc.perform(get("/api/public/barrio/reports/{id}", id)).andReturn().getResponse().getContentAsString();
    String list = mvc.perform(get("/api/public/barrio/reports?status=all")).andReturn().getResponse().getContentAsString();
    for (String json : List.of(detail, list)) {
      assertThat(json).doesNotContain("evidence").doesNotContain("distance").doesNotContain("accuracy")
          .doesNotContain("dhash").doesNotContain("photoTakenAt").doesNotContain("humanDecision")
          .doesNotContain("\"ai\"").doesNotContain("beforePhotoUrl");
    }
  }
}
