package com.fixy.backend.service;

import com.fixy.backend.dto.DisputeResolutionResponse;
import com.fixy.backend.dto.LeadCompletionConfirmRequest;
import com.fixy.backend.dto.LeadCompletionConfirmResponse;
import com.fixy.backend.dto.LeadRatingReplyRequest;
import com.fixy.backend.dto.LeadRatingSubmitRequest;
import com.fixy.backend.dto.LeadResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadRating;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadRatingRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * P0-2: loop de cierre — confirmación del cliente + rating (H2.1, H2.3,
 * H2.6) sobre un lead que el proveedor autodeclaró COMPLETED (P0-1). El
 * aviso inicial al chat (item 6 de la épica) y el scheduler de
 * auto-confirmación (H2.4) viven en {@link LeadClosingScheduler}, pero el
 * mensaje de "avisar que está COMPLETED" se dispara desde acá porque es
 * parte del mismo flujo síncrono que dispara ProviderSelfService.
 */
@Service
public class LeadClosingService {

  private static final int RATING_SCALE = 2;

  private final LeadRepository leadRepository;
  private final ProviderRepository providerRepository;
  private final LeadRatingRepository leadRatingRepository;
  private final LeadTimelineService timelineService;
  private final LeadMessageService leadMessageService;
  private final TelegramNotifyService telegramNotifyService;
  private final CustomerPaymentService customerPaymentService;
  private final PushNotificationService pushNotificationService;

  public LeadClosingService(
      LeadRepository leadRepository,
      ProviderRepository providerRepository,
      LeadRatingRepository leadRatingRepository,
      LeadTimelineService timelineService,
      LeadMessageService leadMessageService,
      TelegramNotifyService telegramNotifyService,
      CustomerPaymentService customerPaymentService,
      PushNotificationService pushNotificationService
  ) {
    this.leadRepository = leadRepository;
    this.providerRepository = providerRepository;
    this.leadRatingRepository = leadRatingRepository;
    this.timelineService = timelineService;
    this.leadMessageService = leadMessageService;
    this.telegramNotifyService = telegramNotifyService;
    this.customerPaymentService = customerPaymentService;
    this.pushNotificationService = pushNotificationService;
  }

  /**
   * Mensaje único al cliente cuando el proveedor marca COMPLETED (item 6).
   * Sin link hardcodeado: el frontend resuelve la URL de confirmación con
   * el accessToken que el cliente ya tiene. Se llama una sola vez desde
   * ProviderSelfService justo después de la transición — no hay riesgo de
   * duplicado porque updateLeadStatus solo dispara este flujo si
   * before != COMPLETED.
   */
  public void notifyCustomerOfCompletion(Lead lead) {
    // Tier 2 (contrato §C.1): el score deja de ser obligatorio en la
    // confirmación — el cierre ya no promete "calificalo del 1 al 5 acá
    // arriba" (eso pasa a pedirse aparte, 24h después, ver
    // ReviewRequestScheduler).
    leadMessageService.postFromOps(lead.getId(), "fixy",
        "El proveedor marcó el trabajo como terminado. Confirmá acá arriba si quedó todo bien.");
  }

  /**
   * H2.1: confirmación (o disputa) del cliente sobre un lead COMPLETED,
   * autenticado por accessToken del LEAD (no del proveedor — así el
   * proveedor no puede confirmar en su propio nombre).
   */
  public LeadCompletionConfirmResponse confirmCompletion(
      Long leadId, String token, LeadCompletionConfirmRequest request
  ) {
    Lead lead = requireLeadAndToken(leadId, token);

    if (lead.getStatus() != LeadStatus.COMPLETED) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "el lead todavía no fue marcado como completado por el proveedor");
    }
    if (leadRatingRepository.existsByLeadId(leadId) || lead.isDisputed()
        || timelineService.hasEvent(leadId, "CUSTOMER_CONFIRMED_COMPLETION")) {
      // Tier 2: la confirmación sin nota también cuenta como "ya confirmado"
      // — sin este guard, un segundo toque (u otro dispositivo) duplicaba el
      // evento y el mensaje. El 409 lo interpreta el front como 'already-done'.
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "este trabajo ya fue confirmado o reportado antes");
    }

    if (Boolean.TRUE.equals(request.confirmed())) {
      return confirmWithRating(lead, request);
    }
    return dispute(lead, request);
  }

  private LeadCompletionConfirmResponse confirmWithRating(Lead lead, LeadCompletionConfirmRequest request) {
    Integer score = request.score();
    if (score != null && (score < 1 || score > 5)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "score debe estar entre 1 y 5");
    }
    timelineService.appendEvent(lead, "CUSTOMER_CONFIRMED_COMPLETION", "user",
        "Cliente confirmó el trabajo completado");

    // Tier 2 (contrato §C.1): score pasa a opcional. Sin score: sin
    // LeadRating, mensaje que anticipa el pedido de reseña de mañana (ver
    // ReviewRequestScheduler) — el pedido de reseña de C.2 no se manda
    // porque ya hay confirmación (aunque sin nota todavía).
    if (score == null) {
      Provider provider = resolveAssignedProvider(lead).orElse(null);
      String technicianName = provider != null && hasText(provider.getName()) ? provider.getName() : "el técnico";
      leadMessageService.postFromOps(lead.getId(), "fixy",
          "Gracias por confirmar. Mañana te pedimos una reseña de %s: dura 30 segundos y ayuda a que otros vecinos lo elijan."
              .formatted(technicianName));
      return new LeadCompletionConfirmResponse(lead.getId(), true, false, null);
    }

    Provider provider = resolveAssignedProvider(lead)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
            "este lead no tiene proveedor asignado, no se puede calificar"));
    createAndNotifyRating(lead, provider, score, request.comment());
    return new LeadCompletionConfirmResponse(lead.getId(), true, false, score);
  }

  /**
   * Tier 2 (contrato §C.3): {@code POST /api/public/leads/{id}/rating} — el
   * rating "propio" del cliente cuando NO lo dejó en el momento de
   * confirmar (C.1, score opcional). 400 si el lead no está COMPLETED, 409
   * si ya hay rating (mismo lead nunca dos reseñas, sin importar el canal).
   */
  public LeadResponse.Rating submitRating(Long leadId, String token, LeadRatingSubmitRequest request) {
    Lead lead = requireLeadAndToken(leadId, token);
    if (lead.getStatus() != LeadStatus.COMPLETED) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "el trabajo todavía no fue marcado como terminado");
    }
    if (leadRatingRepository.existsByLeadId(leadId)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "este pedido ya tiene una reseña");
    }
    if (request.score() == null || request.score() < 1 || request.score() > 5) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "score es obligatorio y debe estar entre 1 y 5");
    }
    Provider provider = resolveAssignedProvider(lead)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
            "este lead no tiene proveedor asignado, no se puede calificar"));

    LeadRating rating = createAndNotifyRating(lead, provider, request.score(), request.comment());

    String technicianName = hasText(provider.getName()) ? provider.getName() : "el técnico";
    String verifiedSuffix = rating.isVerified() ? " verificada" : "";
    leadMessageService.postFromOps(lead.getId(), "fixy",
        "Gracias por tu reseña%s. %s la va a ver en su panel.".formatted(verifiedSuffix, technicianName));

    return new LeadResponse.Rating(rating.getScore(), rating.getComment(), rating.isVerified(),
        rating.getCreatedAt(), rating.getProviderReply(), rating.getProviderReplyAt());
  }

  /**
   * Creación compartida del rating (contrato §C.3: "extraer la creación del
   * rating a un método compartido con confirmWithRating, misma regla de
   * detractores") — agregados del proveedor, evento de timeline, aviso a
   * ops de TODA reseña nueva, y el escalamiento a un humano + aviso extra
   * al vecino cuando es detractor (score <=3). El score NUNCA se modifica
   * ni se oculta.
   */
  private LeadRating createAndNotifyRating(Lead lead, Provider provider, int score, String comment) {
    LeadRating rating = new LeadRating();
    rating.setLeadId(lead.getId());
    rating.setProviderId(provider.getId());
    rating.setScore(score);
    rating.setComment(comment);
    // Refundación fase 2 (contrato §A.4.4): "verificada" cuando el cargo de
    // servicio de este lead ya está PAID en el momento de calificar. Si el
    // pago llega DESPUÉS, CustomerPaymentService.markPaid la verifica ahí.
    rating.setVerified(customerPaymentService.hasServiceFeePaid(lead.getId()));
    rating = leadRatingRepository.save(rating);

    recalculateProviderAggregates(provider);

    timelineService.appendEvent(lead, "RATING_SUBMITTED", "user",
        "Calificación: %d/5%s".formatted(score, (comment == null || comment.isBlank()) ? "" : " — " + comment));

    try {
      pushNotificationService.notifyProvider(provider.getId(), provider.getAccessToken(),
          "Nueva reseña", "Nueva reseña: ★%d".formatted(score));
    } catch (Exception ex) {
      // best-effort, mismo patrón que el resto de los push
    }
    try {
      telegramNotifyService.notifyRatingSubmitted(lead, rating);
    } catch (Exception ex) {
      // best-effort: un aviso a ops que falla no debe romper la reseña
    }

    if (score <= 3) {
      try {
        telegramNotifyService.notifyLowRating(lead, score, comment);
      } catch (Exception ex) {
        // best-effort
      }
      leadMessageService.postFromOps(lead.getId(), "fixy",
          "Gracias por contarlo con franqueza. Una persona de Fixy te escribe hoy para ver cómo lo arreglamos.");
    }

    return rating;
  }

  /**
   * Tier 2 (contrato §C.3): {@code POST
   * /api/public/providers/{id}/leads/{leadId}/rating-reply} — respuesta
   * pública del proveedor asignado a la reseña de ESTE trabajo. Solo el
   * asignado, solo si hay rating, una sola vez (409 si ya respondió).
   */
  public LeadResponse.Rating replyToRating(Provider provider, Long leadId, LeadRatingReplyRequest request) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    if (lead.getAssignedProviderId() == null || !lead.getAssignedProviderId().equals(provider.getId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "lead not assigned to this provider");
    }
    LeadRating rating = leadRatingRepository.findByLeadId(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "este pedido todavía no tiene reseña"));
    if (rating.getProviderReply() != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "ya respondiste esta reseña");
    }
    String text = request.text() == null ? "" : request.text().trim();
    if (text.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "la respuesta no puede estar vacía");
    }
    rating.setProviderReply(text);
    rating.setProviderReplyAt(OffsetDateTime.now());
    leadRatingRepository.save(rating);

    timelineService.appendEvent(lead, "RATING_REPLIED", "provider", text);
    String providerName = hasText(provider.getName()) ? provider.getName() : "El técnico";
    leadMessageService.postFromOps(lead.getId(), "fixy",
        "%s respondió a tu reseña: “%s”".formatted(providerName, text));

    return new LeadResponse.Rating(rating.getScore(), rating.getComment(), rating.isVerified(),
        rating.getCreatedAt(), rating.getProviderReply(), rating.getProviderReplyAt());
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isBlank();
  }

  private LeadCompletionConfirmResponse dispute(Lead lead, LeadCompletionConfirmRequest request) {
    lead.setDisputed(true);
    leadRepository.save(lead);

    String comment = (request.comment() == null || request.comment().isBlank())
        ? "(sin detalle)" : request.comment();
    timelineService.appendEvent(lead, "CUSTOMER_DISPUTED_COMPLETION", "user",
        "Cliente reportó un problema: " + comment);

    // Cierre visible de disputas (Ola 2): evento de vida propia (distinto
    // del evento anterior, que registra el reporte en sí) + mensaje al
    // cliente en el mismo hilo con expectativa concreta, para que no
    // quede en el limbo esperando sin saber si alguien lo va a leer.
    timelineService.appendEvent(lead, "DISPUTE_OPENED", "fixy",
        "Se abrió una disputa para revisión de ops");
    leadMessageService.postFromOps(lead.getId(), "fixy",
        "Gracias por avisarnos. Una persona de Fixy va a revisar esto, te contactamos en menos de 24h.");
    // Le prometimos al cliente "menos de 24h" — ops tiene que enterarse YA.
    telegramNotifyService.notifyDisputeOpened(lead, comment);

    return new LeadCompletionConfirmResponse(lead.getId(), false, true, null);
  }

  /**
   * Cierre visible de disputas (Ola 2): ops (Carlos u otro operador,
   * mismo basic auth de {@code /api/leads/**}) marca la disputa como
   * resuelta con una nota corta. Idempotente por diseño — una segunda
   * resolución sobre el mismo lead se rechaza limpio (409) en vez de
   * pisar la nota anterior o duplicar el mensaje al cliente.
   */
  public DisputeResolutionResponse resolveDispute(Long leadId, String resolutionNote) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));

    if (!lead.isDisputed()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "este lead no tiene una disputa abierta");
    }
    if (lead.getDisputeResolvedAt() != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "esta disputa ya fue resuelta antes");
    }
    if (resolutionNote == null || resolutionNote.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "la nota de resolución es obligatoria");
    }

    lead.setDisputeResolvedAt(OffsetDateTime.now());
    lead.setDisputeResolutionNote(resolutionNote);
    leadRepository.save(lead);

    timelineService.appendEvent(lead, "DISPUTE_RESOLVED", "fixy", resolutionNote);
    leadMessageService.postFromOps(lead.getId(), "fixy",
        "Ya revisamos tu reporte: " + resolutionNote);

    return new DisputeResolutionResponse(
        lead.getId(), lead.isDisputed(), lead.getDisputeResolutionNote(), lead.getDisputeResolvedAt());
  }

  /** H2.3: agregación real desde la tabla, no incremental. */
  private void recalculateProviderAggregates(Provider provider) {
    Double average = leadRatingRepository.averageScoreByProviderId(provider.getId());
    long count = leadRatingRepository.countByProviderId(provider.getId());
    double rounded = average == null
        ? 0.0
        : BigDecimal.valueOf(average).setScale(RATING_SCALE, RoundingMode.HALF_EVEN).doubleValue();
    provider.setRatingAverage(rounded);
    provider.setRatingCount((int) count);
    providerRepository.save(provider);
  }

  /** Resuelve el proveedor asignado por id o, para leads legacy, por
   * nombre. Vacío si el lead no tiene proveedor asignado. */
  private java.util.Optional<Provider> resolveAssignedProvider(Lead lead) {
    if (lead.getAssignedProviderId() != null) {
      return providerRepository.findById(lead.getAssignedProviderId());
    }
    String assignedName = lead.getAssignedProvider();
    if (assignedName != null && !assignedName.isBlank()) {
      return providerRepository.findAllByOrderByCreatedAtDesc().stream()
          .filter(p -> assignedName.equalsIgnoreCase(p.getName()))
          .findFirst();
    }
    return java.util.Optional.empty();
  }

  /** Mismo patrón que LeadMessageService: token del LEAD, no del proveedor. */
  private Lead requireLeadAndToken(Long leadId, String token) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    if (lead.getAccessToken() == null || token == null || !lead.getAccessToken().equals(token)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid token");
    }
    return lead;
  }
}
