package com.fixy.backend.service;

import com.fixy.backend.dto.DisputeResolutionResponse;
import com.fixy.backend.dto.LeadCompletionConfirmRequest;
import com.fixy.backend.dto.LeadCompletionConfirmResponse;
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

  public LeadClosingService(
      LeadRepository leadRepository,
      ProviderRepository providerRepository,
      LeadRatingRepository leadRatingRepository,
      LeadTimelineService timelineService,
      LeadMessageService leadMessageService
  ) {
    this.leadRepository = leadRepository;
    this.providerRepository = providerRepository;
    this.leadRatingRepository = leadRatingRepository;
    this.timelineService = timelineService;
    this.leadMessageService = leadMessageService;
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
    leadMessageService.postFromOps(lead.getId(), "fixy",
        "El proveedor marcó el trabajo como terminado. ¿Confirmás que quedó todo bien? "
            + "Podés calificarlo del 1 al 5 acá arriba.");
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
    if (leadRatingRepository.existsByLeadId(leadId) || lead.isDisputed()) {
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
    if (score == null || score < 1 || score > 5) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "score es obligatorio y debe estar entre 1 y 5 para confirmar");
    }
    Long providerId = requireAssignedProviderId(lead);
    Provider provider = providerRepository.findById(providerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found"));

    LeadRating rating = new LeadRating();
    rating.setLeadId(lead.getId());
    rating.setProviderId(providerId);
    rating.setScore(score);
    rating.setComment(request.comment());
    leadRatingRepository.save(rating);

    recalculateProviderAggregates(provider);

    timelineService.appendEvent(lead, "CUSTOMER_CONFIRMED_COMPLETION", "user",
        "Cliente confirmó el trabajo completado");
    timelineService.appendEvent(lead, "RATING_SUBMITTED", "user",
        "Calificación: %d/5%s".formatted(score,
            (request.comment() == null || request.comment().isBlank()) ? "" : " — " + request.comment()));

    return new LeadCompletionConfirmResponse(lead.getId(), true, false, score);
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

  private Long requireAssignedProviderId(Lead lead) {
    if (lead.getAssignedProviderId() != null) {
      return lead.getAssignedProviderId();
    }
    String assignedName = lead.getAssignedProvider();
    if (assignedName != null && !assignedName.isBlank()) {
      return providerRepository.findAllByOrderByCreatedAtDesc().stream()
          .filter(p -> assignedName.equalsIgnoreCase(p.getName()))
          .map(Provider::getId)
          .findFirst()
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
              "no se pudo resolver el proveedor asignado para calcular el rating"));
    }
    throw new ResponseStatusException(HttpStatus.CONFLICT,
        "este lead no tiene proveedor asignado, no se puede calificar");
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
