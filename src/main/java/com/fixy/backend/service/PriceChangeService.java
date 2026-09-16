package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.PriceChangeStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadPhotoRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tier 1 (contrato TIER1_CONTRATO.md §B): protocolo "al llegar" — el
 * proveedor propone un precio nuevo cuando el trabajo real difiere de lo
 * cerrado (Decreto 244/000 art. 9: todo adicional con aprobación previa) y
 * el cliente lo acepta o lo rechaza. Reusa {@link LeadTimelineService} para
 * el registro y {@link LeadMessageService} para los mensajes deterministas
 * (mismo criterio "lo crítico va en código no en prompt" que {@link OrderService}),
 * y {@link LeadPhotoRepository} para exigir evidencia — mismo chequeo que
 * ya usa {@link ProviderSelfService} para completar un trabajo remoto.
 */
@Service
public class PriceChangeService {

  private static final Logger log = LoggerFactory.getLogger(PriceChangeService.class);
  private static final Set<LeadStatus> ELIGIBLE_STATUSES = Set.of(LeadStatus.ASSIGNED, LeadStatus.IN_PROGRESS);

  private final LeadRepository leadRepository;
  private final LeadPhotoRepository leadPhotoRepository;
  private final ProviderRepository providerRepository;
  private final ProviderSelfService providerSelfService;
  private final LeadTimelineService timelineService;
  private final LeadMessageService leadMessageService;
  private final PushNotificationService pushNotificationService;
  private final TelegramNotifyService telegramNotifyService;

  public PriceChangeService(
      LeadRepository leadRepository,
      LeadPhotoRepository leadPhotoRepository,
      ProviderRepository providerRepository,
      ProviderSelfService providerSelfService,
      LeadTimelineService timelineService,
      LeadMessageService leadMessageService,
      PushNotificationService pushNotificationService,
      // @Lazy: TelegramNotifyService depende (transitivamente, vía @Lazy) de
      // ProviderSelfService, que este servicio también inyecta — sin el
      // proxy perezoso Spring no puede armar el ciclo. Mismo motivo que
      // CustomerPaymentService.
      @org.springframework.context.annotation.Lazy TelegramNotifyService telegramNotifyService
  ) {
    this.leadRepository = leadRepository;
    this.leadPhotoRepository = leadPhotoRepository;
    this.providerRepository = providerRepository;
    this.providerSelfService = providerSelfService;
    this.timelineService = timelineService;
    this.leadMessageService = leadMessageService;
    this.pushNotificationService = pushNotificationService;
    this.telegramNotifyService = telegramNotifyService;
  }

  /**
   * Contrato §B.1: solo en ASSIGNED/IN_PROGRESS; exige >=1 foto subida por
   * el PROVEEDOR (mismo criterio que el guard de completar un trabajo
   * remoto). Si ya había una propuesta sin responder, la reemplaza — y
   * también pisa una propuesta ya aceptada o rechazada antes: una propuesta
   * nueva siempre abre una ventana de decisión limpia.
   */
  public Lead propose(Provider provider, Long leadId, BigDecimal amount, String reason) {
    Lead lead = providerSelfService.requireAssignedLead(provider, leadId);
    if (!ELIGIBLE_STATUSES.contains(lead.getStatus())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "solo se puede proponer un precio nuevo en un trabajo asignado o en curso");
    }
    if (amount == null || amount.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "el monto debe ser mayor a 0");
    }
    if (leadPhotoRepository.countByLeadIdAndProviderIdIsNotNull(lead.getId()) == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Subí una foto de lo que encontraste antes de proponer el precio nuevo");
    }
    String trimmedReason = reason == null ? null : reason.trim();
    if (trimmedReason != null && trimmedReason.isBlank()) {
      trimmedReason = null;
    }

    // Sin forzar scale acá: se persiste tal cual llegó (la columna
    // numeric(12,2) de V30 ya define la precisión real en la base). Forzar
    // BigDecimal.setScale(2) del lado Java solo cambiaría cómo Jackson
    // serializa la respuesta (4500 → "4500.00"), sin ganar nada.
    BigDecimal normalizedAmount = amount;
    OffsetDateTime now = OffsetDateTime.now();
    lead.setProposedAmount(normalizedAmount);
    lead.setProposedReason(trimmedReason);
    lead.setProposedAt(now);
    // Ventana de decisión limpia: una propuesta nueva pisa cualquier
    // aceptación/rechazo anterior (ver PriceChangeStatus.of).
    lead.setAgreedAmount(null);
    lead.setAgreedAt(null);
    lead.setPriceChangeRejectedAt(null);
    leadRepository.save(lead);

    String reasonForTimeline = trimmedReason == null ? "sin detalle" : trimmedReason;
    timelineService.appendEvent(lead, "PRICE_CHANGE_PROPOSED", "provider",
        "Propuso $%s (motivo: %s)".formatted(formatMoney(normalizedAmount), reasonForTimeline));

    String technicianName = hasText(provider.getName()) ? provider.getName() : "El técnico";
    String reasonClause = trimmedReason == null ? "" : " (motivo: " + trimmedReason + ")";
    String message = ("%s revisó el trabajo y propone un precio nuevo: **$%s**%s. Mirá la foto en el chat. "
        + "Nada se hace hasta que aceptes.")
        .formatted(technicianName, formatMoney(normalizedAmount), reasonClause);
    leadMessageService.postFromOps(lead.getId(), "fixy", message);

    try {
      pushNotificationService.notifyLeadHasNews(lead.getId(), "Fixy: el técnico propone un precio nuevo", message);
    } catch (Exception ex) {
      log.warn("push de propuesta de precio falló para lead {}: {}", lead.getId(), ex.getMessage());
    }

    return lead;
  }

  /** Contrato §B.1: autenticado por el accessToken del LEAD (no del
   * proveedor), mismo patrón que {@link LeadClosingService}. */
  public Lead accept(Long leadId, String token) {
    Lead lead = requireLeadAndToken(leadId, token);
    if (PriceChangeStatus.of(lead) != PriceChangeStatus.PENDING) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "no hay una propuesta de precio pendiente para aceptar");
    }

    lead.setAgreedAmount(lead.getProposedAmount());
    lead.setAgreedAt(OffsetDateTime.now());
    leadRepository.save(lead);

    // Registro legal (contrato §B.1: "no se borra nunca") — actor customer
    // con el monto explícito en el mensaje del timeline.
    timelineService.appendEvent(lead, "PRICE_CHANGE_ACCEPTED", "customer",
        "Aceptó $%s".formatted(formatMoney(lead.getAgreedAmount())));

    leadMessageService.postFromOps(lead.getId(), "fixy",
        "Aceptaste $%s. El técnico ya puede seguir.".formatted(formatMoney(lead.getAgreedAmount())));

    notifyProviderBestEffort(lead, "Fixy: el vecino aceptó el precio nuevo",
        "Aceptó $" + formatMoney(lead.getAgreedAmount()) + ". Ya podés seguir.");

    return lead;
  }

  /** Contrato §B.1: idem accept — token del lead. */
  public Lead reject(Long leadId, String token) {
    Lead lead = requireLeadAndToken(leadId, token);
    if (PriceChangeStatus.of(lead) != PriceChangeStatus.PENDING) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "no hay una propuesta de precio pendiente para rechazar");
    }

    lead.setPriceChangeRejectedAt(OffsetDateTime.now());
    leadRepository.save(lead);

    timelineService.appendEvent(lead, "PRICE_CHANGE_REJECTED", "customer",
        "No aceptó el precio nuevo ($%s)".formatted(formatMoney(lead.getProposedAmount())));

    leadMessageService.postFromOps(lead.getId(), "fixy",
        "Avisamos al técnico y a Fixy. Una persona te escribe.");

    notifyProviderBestEffort(lead, "Fixy: el vecino no aceptó el precio nuevo",
        "No aceptó — Fixy está revisando, no sigas todavía.");

    try {
      telegramNotifyService.notifyPriceChangeRejected(lead, lead.getProposedAmount(), lead.getProposedReason());
    } catch (Exception ex) {
      log.warn("aviso a ops de rechazo de cambio de precio falló para lead {}: {}", lead.getId(), ex.getMessage());
    }

    return lead;
  }

  private void notifyProviderBestEffort(Lead lead, String title, String body) {
    try {
      if (lead.getAssignedProviderId() == null) return;
      Provider provider = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
      if (provider == null) return;
      pushNotificationService.notifyProvider(provider.getId(), provider.getAccessToken(), title, body);
    } catch (Exception ex) {
      log.warn("push a proveedor falló para lead {}: {}", lead.getId(), ex.getMessage());
    }
  }

  private Lead requireLeadAndToken(Long leadId, String token) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    if (lead.getAccessToken() == null || token == null || !lead.getAccessToken().equals(token)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid token");
    }
    return lead;
  }

  private String formatMoney(BigDecimal amount) {
    if (amount == null) {
      return "";
    }
    return ServiceCatalogService.formatUyu(amount.setScale(0, RoundingMode.HALF_UP).intValueExact());
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
