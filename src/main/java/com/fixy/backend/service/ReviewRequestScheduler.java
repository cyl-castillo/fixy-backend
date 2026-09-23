package com.fixy.backend.service;

import com.fixy.backend.model.CustomerPaymentKind;
import com.fixy.backend.model.CustomerPaymentStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.SmokeTraffic;
import com.fixy.backend.repository.CustomerPaymentRepository;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRatingRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Tier 2 (contrato §C.2): pedido de reseña a las {@code
 * fixy.reviews.request.after-hours} (default 24) del pago del cargo de
 * servicio (o del COMPLETED si no pagó, mismo criterio de ancla que {@link
 * LeadClosingScheduler}). Una sola vez por lead (evento {@code
 * REVIEW_REQUESTED}); nunca un segundo pedido — si el vecino paga DESPUÉS,
 * {@link CustomerPaymentService#markPaid} ya verifica la reseña
 * retroactivamente si llega después de este mensaje.
 */
@Service
public class ReviewRequestScheduler {

  private static final Logger log = LoggerFactory.getLogger(ReviewRequestScheduler.class);
  static final String REVIEW_REQUESTED_EVENT_TYPE = "REVIEW_REQUESTED";
  /** Ancla demasiado vieja: se marca y no se pide (una reseña un mes tarde no sirve y molesta). */
  static final String REVIEW_REQUEST_SKIPPED_EVENT_TYPE = "REVIEW_REQUEST_SKIPPED";
  private static final String STATUS_CHANGE_EVENT_TYPE = "PROVIDER_STATUS_CHANGE";
  private static final String COMPLETED_SUFFIX = "→ " + LeadStatus.COMPLETED;

  private final LeadRepository leadRepository;
  private final LeadEventRepository leadEventRepository;
  private final LeadRatingRepository leadRatingRepository;
  private final CustomerPaymentRepository customerPaymentRepository;
  private final ProviderRepository providerRepository;
  private final LeadTimelineService timelineService;
  private final LeadMessageService messageService;
  private final PushNotificationService pushNotificationService;

  private final boolean enabled;
  private final long afterHours;
  private final long maxAgeDays;
  private final String publicAppBaseUrl;
  private final Clock clock;

  public ReviewRequestScheduler(
      LeadRepository leadRepository,
      LeadEventRepository leadEventRepository,
      LeadRatingRepository leadRatingRepository,
      CustomerPaymentRepository customerPaymentRepository,
      ProviderRepository providerRepository,
      LeadTimelineService timelineService,
      LeadMessageService messageService,
      PushNotificationService pushNotificationService,
      @Value("${fixy.reviews.request.enabled:true}") boolean enabled,
      @Value("${fixy.reviews.request.after-hours:24}") long afterHours,
      @Value("${fixy.reviews.request.max-age-days:7}") long maxAgeDays,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl,
      Clock clock
  ) {
    this.leadRepository = leadRepository;
    this.leadEventRepository = leadEventRepository;
    this.leadRatingRepository = leadRatingRepository;
    this.customerPaymentRepository = customerPaymentRepository;
    this.providerRepository = providerRepository;
    this.timelineService = timelineService;
    this.messageService = messageService;
    this.pushNotificationService = pushNotificationService;
    this.enabled = enabled;
    this.afterHours = afterHours;
    this.maxAgeDays = maxAgeDays;
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.reviews.request.scheduler-fixed-delay-ms:3600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int processed = processOnce();
    if (processed > 0) {
      log.info("pedido de reseña 24h: {} lead(s) procesados", processed);
    }
  }

  /** Un ciclo del job, invocable desde tests. Devuelve cuántos pedidos de reseña mandó. */
  public int processOnce() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    List<Lead> candidates = leadRepository.findByStatusOrderByCreatedAtDesc(LeadStatus.COMPLETED);
    int actions = 0;
    for (Lead lead : candidates) {
      if (!isEligible(lead)) {
        continue;
      }
      OffsetDateTime anchor = anchorFor(lead);
      if (anchor == null || Duration.between(anchor, now).toHours() < afterHours) {
        continue;
      }
      if (Duration.between(anchor, now).toDays() > maxAgeDays) {
        // Trabajo viejo (backlog previo al Tier 2, o el job estuvo apagado):
        // no se pide, y queda marcado para no re-evaluarlo cada hora.
        timelineService.appendEvent(lead, REVIEW_REQUEST_SKIPPED_EVENT_TYPE, "system",
            "Reseña no pedida: el trabajo terminó hace más de %d días".formatted(maxAgeDays));
        continue;
      }
      sendReviewRequest(lead);
      actions++;
    }
    return actions;
  }

  private boolean isEligible(Lead lead) {
    if (lead.getId() == null || lead.isDisputed()) {
      return false;
    }
    if (SmokeTraffic.marks(lead.getProblem())) {
      return false;
    }
    if (leadRatingRepository.existsByLeadId(lead.getId())) {
      return false;
    }
    return !timelineService.hasEvent(lead.getId(), REVIEW_REQUESTED_EVENT_TYPE)
        && !timelineService.hasEvent(lead.getId(), REVIEW_REQUEST_SKIPPED_EVENT_TYPE);
  }

  /** Ancla: {@code paidAt} del cargo de servicio PAID si existe; si no, la
   * fecha del COMPLETED (mismo patrón que {@link LeadClosingScheduler}). */
  private OffsetDateTime anchorFor(Lead lead) {
    OffsetDateTime paidAt = customerPaymentRepository.findByLeadId(lead.getId())
        .filter(p -> p.getKind() == CustomerPaymentKind.SERVICE_FEE && p.getStatus() == CustomerPaymentStatus.PAID)
        .map(com.fixy.backend.model.CustomerPayment::getPaidAt)
        .orElse(null);
    if (paidAt != null) {
      return paidAt;
    }
    return findCompletedAt(lead.getId());
  }

  private OffsetDateTime findCompletedAt(Long leadId) {
    return leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, STATUS_CHANGE_EVENT_TYPE).stream()
        .filter(event -> event.getMessage() != null && event.getMessage().endsWith(COMPLETED_SUFFIX))
        .map(LeadEvent::getCreatedAt)
        .findFirst()
        .orElse(null);
  }

  private void sendReviewRequest(Lead lead) {
    Provider provider = lead.getAssignedProviderId() == null
        ? null : providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
    String technicianName = provider != null && hasText(provider.getName()) ? provider.getName() : "el técnico";
    boolean paid = customerPaymentRepository.findByLeadId(lead.getId())
        .filter(p -> p.getKind() == CustomerPaymentKind.SERVICE_FEE)
        .map(p -> p.getStatus() == CustomerPaymentStatus.PAID)
        .orElse(false);
    String url = "%s/r/%d/%s".formatted(publicAppBaseUrl, lead.getId(), lead.getAccessToken());
    String message = "¿Cómo te fue con **%s**? Dejá tu reseña en 30 segundos: %s.".formatted(technicianName, url)
        + (paid ? " Va a aparecer como **reseña verificada**." : "");
    try {
      messageService.postFromOps(lead.getId(), "fixy", message);
    } catch (Exception ex) {
      log.warn("review request: no se pudo mandar el mensaje al cliente del lead {}: {}", lead.getId(), ex.getMessage());
    }
    try {
      pushNotificationService.notifyLeadHasNews(lead.getId(), "¿Cómo te fue con %s?".formatted(technicianName), message, url);
    } catch (Exception ex) {
      log.warn("review request: push falló para el lead {}: {}", lead.getId(), ex.getMessage());
    }
    timelineService.appendEvent(lead, REVIEW_REQUESTED_EVENT_TYPE, "system",
        "Pedido de reseña enviado (%s)".formatted(paid ? "pago verificado" : "sin pago"));
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isBlank();
  }
}
