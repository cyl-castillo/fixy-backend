package com.fixy.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.core.agent.AgentTurnRecorder;
import com.fixy.backend.core.agent.LlmGateway;
import com.fixy.backend.model.BarrioReport;
import com.fixy.backend.repository.BarrioReportRepository;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * IA revisora de Avisá, Fase 1 / modo sombra (AVISA_IA_F1_CONTRATO.md): mira la foto de un reclamo
 * y guarda una opinión; {@link BarrioAiVerdict} la traduce a APROBAR / DUDOSO. NO aprueba ni
 * rechaza nunca; en la Fase 2 puede auto-publicar los casos claros ({@link BarrioAutoPublishPolicy}). Corre fuera de
 * transacción (la llamada dura hasta 45 s): cada {@code save} commitea solo.
 */
@Service
public class BarrioAiReviewService {

  private static final Logger log = LoggerFactory.getLogger(BarrioAiReviewService.class);

  public static final String KIND = "BARRIO_REVIEW";
  public static final int MAX_ATTEMPTS = 3;
  static final int SUMMARY_MAX = 200;
  private static final Set<String> SUGGESTED = Set.of("bache", "poda", "luminaria", "ninguna");

  static final String SYSTEM_PROMPT = """
      Sos el revisor de fotos de Avisá, una web donde vecinos de Ciudad de la Costa (Uruguay) reportan \
      problemas en la vía pública. Mirá la foto y respondé SOLO el JSON pedido. No decidís nada: \
      describís lo que ves.
      Categorías:
      - bache: pozo o rotura de calzada o vereda.
      - poda: ramas, troncos o restos vegetales en la calle o la vereda.
      - luminaria: foco o farol de alumbrado público apagado o roto (de noche o con el poste visible).
      Marcá has_people_faces si se ve la cara de una persona, y has_license_plates si una matrícula \
      es legible. offensive_or_inappropriate es para contenido ofensivo, obsceno o ajeno al reclamo. \
      confidence va de 0 a 1. reason: una frase corta en español (máximo 200 caracteres).""";

  static final String PROMPT_VERSION = AgentTurnRecorder.promptVersion(SYSTEM_PROMPT);

  private final BarrioReportRepository reports;
  private final BarrioPhotoService photos;
  private final LlmGateway llm;
  private final AgentTurnRecorder recorder;
  private final TelegramNotifyService telegram;
  private final ObjectMapper mapper;
  private final boolean enabled;
  private final BarrioSettingsService settings;
  private final BarrioService barrio;

  @Autowired
  public BarrioAiReviewService(
      BarrioReportRepository reports,
      BarrioPhotoService photos,
      LlmGateway llm,
      AgentTurnRecorder recorder,
      TelegramNotifyService telegram,
      ObjectMapper mapper,
      @Value("${fixy.barrio.ai-review.enabled:true}") boolean enabled,
      BarrioSettingsService settings,
      // Lazy: BarrioService depende de este servicio (alta) y este de BarrioService (auto-publicar).
      @Lazy BarrioService barrio
  ) {
    this.settings = settings;
    this.barrio = barrio;
    this.reports = reports;
    this.photos = photos;
    this.llm = llm;
    this.recorder = recorder;
    this.telegram = telegram;
    this.mapper = mapper;
    this.enabled = enabled;
  }

  /** La revisión está encendida y hay con qué hacerla (key de OpenAI). */
  public boolean isActive() {
    return enabled && llm.hasOpenAiKey();
  }

  /** Revisa un reclamo: nunca lanza. Al terminar (veredicto válido o intentos agotados) avisa por Telegram. */
  public void review(BarrioReport report) {
    String path = report.getPhotoPath();
    String userText = "Categoría declarada por el vecino: " + report.getCategory() + ". Revisá la foto.";
    String auditContent = SYSTEM_PROMPT + "\n\n" + userText + "\n[foto " + path + ", reporte #" + report.getId() + "]";
    AgentTurnRecorder.Handle handle = recorder.start(KIND, null, PROMPT_VERSION, auditContent);
    String raw = null;
    String verdict;
    BarrioAiVerdict.Result result = null;
    BarrioAiVerdict.Opinion opinion = null;
    try {
      byte[] jpeg = photos.read(path);
      raw = llm.completeJsonWithImage(SYSTEM_PROMPT, userText, jpeg, "image/jpeg", schema());
      handle.stopClock();
      opinion = parse(raw, report.getCategory());
      result = BarrioAiVerdict.evaluate(opinion);
      verdict = result.verdict();
    } catch (RuntimeException ex) {
      handle.stopClock();
      log.warn("barrio IA #{}: no se pudo revisar: {}", report.getId(), ex.getMessage());
      verdict = BarrioAiVerdict.ERROR;
    }
    recorder.finish(handle, raw, verdict, result == null ? List.of() : result.reasons());

    // El humano pudo decidir mientras la IA miraba: se relee y solo se tocan los campos ai_*.
    BarrioReport fresh = reports.findById(report.getId()).orElse(null);
    if (fresh == null) return;
    report = fresh;
    int attempts = report.getAiAttempts() + 1;
    report.setAiAttempts(attempts);
    report.setAiVerdict(verdict);
    report.setAiReviewedAt(OffsetDateTime.now());
    if (opinion != null) {
      report.setAiConfidence(opinion.confidence());
      report.setAiReasons(String.join(",", result.reasons()));
      report.setAiSuggestedCategory(opinion.suggestedCategory());
      report.setAiSummary(opinion.reason());
    }
    reports.save(report);

    boolean stillPending = BarrioReport.PENDING_REVIEW.equals(report.getStatus());
    if (stillPending && (!BarrioAiVerdict.ERROR.equals(verdict) || attempts >= MAX_ATTEMPTS)) {
      afterReview(report);
    }
  }

  /** Decide (código, no IA) si se auto-publica; si no, el aviso de pendiente de siempre. */
  private void afterReview(BarrioReport report) {
    BarrioAutoPublishPolicy.Decision decision = BarrioAutoPublishPolicy.Decision.NO;
    try {
      decision = BarrioAutoPublishPolicy.decide(settings.autoPublish(), report.getAiVerdict(),
          report.getAiConfidence(), settings.minConfidence(), report.getStatus(), report.getDescription(),
          barrio.publishedTodayByAi(), settings.dailyCap());
      if (decision == BarrioAutoPublishPolicy.Decision.PUBLISH) {
        var published = barrio.autoPublish(report.getId());
        if (published.isPresent()) {
          log.info("barrio IA #{}: auto-publicado ({})", report.getId(), published.get().status());
          notifyAutoPublished(report, published.get().mergedIntoId());
          return;
        }
        return; // alguien lo decidió en el medio: ya no está pendiente, no hay nada que avisar
      }
    } catch (RuntimeException e) {
      log.warn("barrio IA #{}: auto-publicación falló, queda pendiente: {}", report.getId(), e.getMessage());
      decision = BarrioAutoPublishPolicy.Decision.NO;
    }
    notifyTelegram(report, decision == BarrioAutoPublishPolicy.Decision.DAILY_CAP_REACHED);
  }

  private void notifyAutoPublished(BarrioReport r, Long mergedIntoId) {
    try {
      telegram.notifyBarrioAutoPublished(r.getId(), BarrioService.labelOf(r.getCategory()), r.getZone(),
          r.getDescription(), pct(r.getAiConfidence()), mergedIntoId);
    } catch (RuntimeException e) {
      log.warn("telegram barrio auto-publicado #{} falló: {}", r.getId(), e.getMessage());
    }
  }

  /** Línea de opinión para Telegram. */
  public static String telegramLine(BarrioReport r) {
    String v = r.getAiVerdict();
    String line;
    if (BarrioAiVerdict.APROBAR.equals(v)) {
      line = "IA: aprobar (" + pct(r.getAiConfidence()) + " %)";
    } else if (BarrioAiVerdict.DUDOSO.equals(v)) {
      List<String> codes = splitReasons(r.getAiReasons());
      line = "IA: dudoso" + (codes.isEmpty() ? "" : " — " + BarrioAiVerdict.humanReasons(codes));
    } else if (BarrioAiVerdict.ERROR.equals(v)) {
      return "IA: no pudo revisar";
    } else {
      return "IA: sin revisión automática";
    }
    String suggested = r.getAiSuggestedCategory();
    if (suggested != null && !"ninguna".equals(suggested) && !suggested.equals(r.getCategory())) {
      line += ". La IA cree que es: " + BarrioService.labelOf(suggested);
    }
    return line;
  }

  public void notifyTelegram(BarrioReport r) {
    notifyTelegram(r, false);
  }

  void notifyTelegram(BarrioReport r, boolean dailyCapReached) {
    try {
      telegram.notifyBarrioReport(r.getId(), BarrioService.labelOf(r.getCategory()), r.getZone(),
          r.getDescription(), telegramLine(r) + (dailyCapReached ? " (tope diario de la IA alcanzado)" : ""));
    } catch (RuntimeException e) {
      log.warn("telegram barrio report #{} falló: {}", r.getId(), e.getMessage());
    }
  }

  static List<String> splitReasons(String csv) {
    if (csv == null || csv.isBlank()) return List.of();
    return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private static long pct(Double c) {
    return Math.round((c == null ? 0 : c) * 100);
  }

  private BarrioAiVerdict.Opinion parse(String raw, String declaredCategory) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException("respuesta vacía");
    }
    try {
      JsonNode n = mapper.readTree(raw);
      double confidence = requireNumber(n, "confidence");
      if (confidence < 0 || confidence > 1 || Double.isNaN(confidence)) {
        throw new IllegalStateException("confidence fuera de rango");
      }
      String suggested = requireText(n, "suggested_category").toLowerCase(Locale.ROOT);
      if (!SUGGESTED.contains(suggested)) {
        throw new IllegalStateException("suggested_category inválida");
      }
      String reason = requireText(n, "reason").trim();
      if (reason.length() > SUMMARY_MAX) reason = reason.substring(0, SUMMARY_MAX);
      return new BarrioAiVerdict.Opinion(
          requireBool(n, "shows_public_issue"), requireBool(n, "matches_category"), suggested,
          requireBool(n, "has_people_faces"), requireBool(n, "has_license_plates"),
          requireBool(n, "offensive_or_inappropriate"), requireBool(n, "image_quality_ok"),
          confidence, reason);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("JSON inválido");
    }
  }

  private static boolean requireBool(JsonNode n, String f) {
    if (!n.path(f).isBoolean()) throw new IllegalStateException("falta " + f);
    return n.get(f).asBoolean();
  }

  private static double requireNumber(JsonNode n, String f) {
    if (!n.path(f).isNumber()) throw new IllegalStateException("falta " + f);
    return n.get(f).asDouble();
  }

  private static String requireText(JsonNode n, String f) {
    if (!n.path(f).isTextual()) throw new IllegalStateException("falta " + f);
    return n.get(f).asText();
  }

  /** JSON Schema estricto (todas las propiedades obligatorias, sin extras). */
  static Map<String, Object> schema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("shows_public_issue", Map.of("type", "boolean"));
    props.put("matches_category", Map.of("type", "boolean"));
    props.put("suggested_category", Map.of("type", "string", "enum", List.of("bache", "poda", "luminaria", "ninguna")));
    props.put("has_people_faces", Map.of("type", "boolean"));
    props.put("has_license_plates", Map.of("type", "boolean"));
    props.put("offensive_or_inappropriate", Map.of("type", "boolean"));
    props.put("image_quality_ok", Map.of("type", "boolean"));
    props.put("confidence", Map.of("type", "number"));
    props.put("reason", Map.of("type", "string"));
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", props);
    schema.put("required", List.copyOf(props.keySet()));
    schema.put("additionalProperties", false);
    return schema;
  }
}
