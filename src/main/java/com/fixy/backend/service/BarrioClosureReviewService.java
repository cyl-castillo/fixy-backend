package com.fixy.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.core.agent.AgentTurnRecorder;
import com.fixy.backend.core.agent.LlmGateway;
import com.fixy.backend.model.BarrioClosureClaim;
import com.fixy.backend.model.BarrioReport;
import com.fixy.backend.repository.BarrioClosureClaimRepository;
import com.fixy.backend.repository.BarrioReportRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * IA de cierres de Avisá, C1 / modo sombra (AVISA_CIERRE_C1_CONTRATO.md): compara la foto del
 * reclamo (ANTES) con la del cierre (DESPUÉS) y solo describe; el veredicto lo da
 * {@link BarrioClosureEvidence}. NO aprueba ni rechaza nada. Corre fuera de transacción (la llamada
 * dura hasta 45 s): cada {@code save} commitea solo.
 */
@Service
public class BarrioClosureReviewService {

  private static final Logger log = LoggerFactory.getLogger(BarrioClosureReviewService.class);

  public static final String KIND = "BARRIO_CLOSURE_REVIEW";
  public static final int MAX_ATTEMPTS = BarrioAiReviewService.MAX_ATTEMPTS;
  static final int SUMMARY_MAX = 200;

  public static final String REVISADO = "REVISADO";
  public static final String ERROR = "ERROR";
  public static final String SIN_IA = "SIN_IA";

  static final String SYSTEM_PROMPT = """
      Sos el revisor de cierres de Avisá, una web donde vecinos de Ciudad de la Costa (Uruguay) \
      reportan problemas en la vía pública. Te paso DOS fotos: la 1 es ANTES (la del reclamo) y la 2 \
      es DESPUÉS (la que dice que ya lo arreglaron). Respondé SOLO el JSON pedido. No decidís nada: \
      describís lo que ves.
      - same_place: las dos fotos son del mismo lugar; juzgalo por cercos, postes, casas, cordón, \
      árboles y vereda.
      - issue_gone: el problema de la categoría ya no está en la foto 2. Bache: el pozo está tapado \
      o reparado. Poda: ya no hay ramas ni restos. Luminaria: el foco funciona (de noche, o con la \
      lámpara visiblemente encendida).
      - looks_like_reused_photo: la foto 2 es la misma foto 1, o una captura de pantalla, o una foto \
      de otra pantalla.
      - has_people_faces: se ve la cara de una persona. has_license_plates: una matrícula es legible. \
      image_quality_ok: la foto 2 se puede evaluar.
      confidence va de 0 a 1. reason: una frase corta en español (máximo 200 caracteres).""";

  static final String PROMPT_VERSION = AgentTurnRecorder.promptVersion(SYSTEM_PROMPT);

  private final BarrioClosureClaimRepository closures;
  private final BarrioReportRepository reports;
  private final BarrioPhotoService photos;
  private final LlmGateway llm;
  private final AgentTurnRecorder recorder;
  private final TelegramNotifyService telegram;
  private final ObjectMapper mapper;
  private final boolean enabled;

  public BarrioClosureReviewService(
      BarrioClosureClaimRepository closures,
      BarrioReportRepository reports,
      BarrioPhotoService photos,
      LlmGateway llm,
      AgentTurnRecorder recorder,
      TelegramNotifyService telegram,
      ObjectMapper mapper,
      @Value("${fixy.barrio.ai-review.enabled:true}") boolean enabled
  ) {
    this.closures = closures;
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

  /** Revisa un cierre: nunca lanza. Al terminar (veredicto válido o intentos agotados) avisa por Telegram. */
  public void review(BarrioClosureClaim claim) {
    BarrioReport report = reports.findById(claim.getReportId()).orElse(null);
    if (report == null) return;
    String beforePath = report.getPhotoPath();
    String afterPath = claim.getPhotoPath();
    String userText = "Categoría del reclamo: " + report.getCategory()
        + ". Foto 1 = ANTES, foto 2 = DESPUÉS. Compará.";
    String auditContent = SYSTEM_PROMPT + "\n\n" + userText + "\n[antes " + beforePath + ", después " + afterPath
        + ", cierre #" + claim.getId() + " del reporte #" + report.getId() + "]";
    AgentTurnRecorder.Handle handle = recorder.start(KIND, null, PROMPT_VERSION, auditContent);
    String raw = null;
    String aiVerdict;
    BarrioClosureEvidence.AiOpinion opinion = null;
    try {
      raw = llm.completeJsonWithImages(SYSTEM_PROMPT, userText,
          List.of(photos.read(beforePath), photos.read(afterPath)), "image/jpeg", schema());
      handle.stopClock();
      opinion = parse(raw);
      aiVerdict = REVISADO;
    } catch (RuntimeException ex) {
      handle.stopClock();
      log.warn("barrio IA cierre #{}: no se pudo revisar: {}", claim.getId(), ex.getMessage());
      aiVerdict = ERROR;
    }

    // El humano pudo decidir mientras la IA miraba: se relee y solo se tocan ai_* y evidence_*.
    BarrioClosureClaim fresh = closures.findById(claim.getId()).orElse(null);
    if (fresh == null) {
      recorder.finish(handle, raw, aiVerdict, List.of());
      return;
    }
    claim = fresh;
    int attempts = claim.getAiAttempts() + 1;
    claim.setAiAttempts(attempts);
    claim.setAiVerdict(aiVerdict);
    claim.setAiReviewedAt(OffsetDateTime.now());
    if (opinion != null) {
      claim.setAiSamePlace(opinion.samePlace());
      claim.setAiIssueGone(opinion.issueGone());
      claim.setAiReused(opinion.reused());
      claim.setAiConfidence(opinion.confidence());
      claim.setAiSummary(opinion.reason());
    }
    BarrioClosureEvidence.Result result = BarrioClosureEvidence.evaluate(
        factsOf(claim, report), aiVerdict, opinion, attempts, MAX_ATTEMPTS);
    claim.setEvidenceVerdict(result.verdict());
    claim.setEvidenceReasons(String.join(",", result.reasons()));
    if (opinion != null) {
      claim.setAiReasons(String.join(",", BarrioClosureEvidence.aiReasons(result.reasons())));
    }
    closures.save(claim);
    recorder.finish(handle, raw, aiVerdict, result.reasons());

    boolean stillPending = BarrioClosureClaim.PENDING_REVIEW.equals(claim.getStatus());
    if (stillPending && (!ERROR.equals(aiVerdict) || attempts >= MAX_ATTEMPTS)) {
      notifyTelegram(claim, report);
    }
  }

  static BarrioClosureEvidence.Facts factsOf(BarrioClosureClaim c, BarrioReport report) {
    return new BarrioClosureEvidence.Facts(
        c.getDistanceM(), c.getAccuracyM(), c.getPhotoTakenAt(), report.getCreatedAt(), c.getDhashMinDistance());
  }

  /** Aviso a Carlos con la línea de pruebas; nunca lanza. */
  public void notifyTelegram(BarrioClosureClaim claim, BarrioReport report) {
    try {
      String line = BarrioClosureEvidence.telegramLine(
          claim.getEvidenceVerdict(), splitReasons(claim.getEvidenceReasons()), claim.getDistanceM(),
          claim.getAiConfidence(), claim.getAiSamePlace(), claim.getAiIssueGone());
      telegram.notifyBarrioClosure(report.getId(), BarrioService.labelOf(report.getCategory()), report.getZone(),
          claim.getComment(), line);
    } catch (RuntimeException e) {
      log.warn("telegram barrio cierre #{} falló: {}", claim.getId(), e.getMessage());
    }
  }

  static List<String> splitReasons(String csv) {
    return BarrioAiReviewService.splitReasons(csv);
  }

  private BarrioClosureEvidence.AiOpinion parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException("respuesta vacía");
    }
    try {
      JsonNode n = mapper.readTree(raw);
      if (!n.path("confidence").isNumber()) throw new IllegalStateException("falta confidence");
      double confidence = n.get("confidence").asDouble();
      if (confidence < 0 || confidence > 1 || Double.isNaN(confidence)) {
        throw new IllegalStateException("confidence fuera de rango");
      }
      if (!n.path("reason").isTextual()) throw new IllegalStateException("falta reason");
      String reason = n.get("reason").asText().trim();
      if (reason.length() > SUMMARY_MAX) reason = reason.substring(0, SUMMARY_MAX);
      return new BarrioClosureEvidence.AiOpinion(
          bool(n, "same_place"), bool(n, "issue_gone"), bool(n, "looks_like_reused_photo"),
          bool(n, "has_people_faces"), bool(n, "has_license_plates"), bool(n, "image_quality_ok"),
          confidence, reason);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("JSON inválido");
    }
  }

  private static boolean bool(JsonNode n, String f) {
    if (!n.path(f).isBoolean()) throw new IllegalStateException("falta " + f);
    return n.get(f).asBoolean();
  }

  /** JSON Schema estricto (todas las propiedades obligatorias, sin extras). */
  static Map<String, Object> schema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("same_place", Map.of("type", "boolean"));
    props.put("issue_gone", Map.of("type", "boolean"));
    props.put("looks_like_reused_photo", Map.of("type", "boolean"));
    props.put("has_people_faces", Map.of("type", "boolean"));
    props.put("has_license_plates", Map.of("type", "boolean"));
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
