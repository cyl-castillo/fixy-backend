package com.fixy.backend.service;

import com.fixy.backend.dto.LeadResponse;
import com.fixy.backend.dto.ProviderStatsResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * API self-service del proveedor: el proveedor accede con
 * (providerId, accessToken) y opera sus leads asignados.
 *
 * Auth simple: Carlos (ops) genera el token vía
 * {@code POST /api/providers/{id}/access-token} y comparte la URL
 * {@code https://www.fixy.com.uy/p/{id}/{token}} con el proveedor por
 * WhatsApp manual.
 */
@Service
public class ProviderSelfService {

  /** Statuses que el proveedor puede setear desde su panel. */
  private static final Set<LeadStatus> PROVIDER_TRANSITIONS = Set.of(
      LeadStatus.ASSIGNED,
      LeadStatus.IN_PROGRESS,
      LeadStatus.COMPLETED,
      LeadStatus.CANCELLED
  );

  /** Tipo de evento de timeline para "voy en camino" (leído por el cliente
   *  desde el timeline público existente, contrato acordado con el otro
   *  agente que construye la superficie del cliente). */
  static final String ON_THE_WAY_EVENT_TYPE = "PROVIDER_ON_THE_WAY";

  /** Anti-spam: como máximo un aviso "voy en camino" por hora por lead. */
  private static final Duration ON_THE_WAY_COOLDOWN = Duration.ofHours(1);

  private final ProviderRepository providerRepository;
  private final LeadRepository leadRepository;
  private final LeadEventRepository leadEventRepository;
  private final LeadTimelineService timelineService;
  private final CommissionService commissionService;
  private final LeadClosingService leadClosingService;
  private final LeadMessageService leadMessageService;
  private final PushNotificationService pushNotificationService;
  private final boolean paymentsEnabled;

  public ProviderSelfService(
      ProviderRepository providerRepository,
      LeadRepository leadRepository,
      LeadEventRepository leadEventRepository,
      LeadTimelineService timelineService,
      CommissionService commissionService,
      LeadClosingService leadClosingService,
      LeadMessageService leadMessageService,
      PushNotificationService pushNotificationService,
      @Value("${fixy.payments.enabled:false}") boolean paymentsEnabled
  ) {
    this.providerRepository = providerRepository;
    this.leadRepository = leadRepository;
    this.leadEventRepository = leadEventRepository;
    this.timelineService = timelineService;
    this.commissionService = commissionService;
    this.leadClosingService = leadClosingService;
    this.leadMessageService = leadMessageService;
    this.pushNotificationService = pushNotificationService;
    this.paymentsEnabled = paymentsEnabled;
  }

  public Provider authenticate(Long providerId, String token) {
    Provider provider = providerRepository.findById(providerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found"));
    if (provider.getAccessToken() == null
        || token == null
        || !provider.getAccessToken().equals(token)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid token");
    }
    return provider;
  }

  public List<Lead> assignedLeadsFor(Provider provider) {
    // Union: por ID (asignaciones nuevas) y por nombre (legacy).
    List<Lead> byId = leadRepository.findByAssignedProviderIdOrderByCreatedAtDesc(provider.getId());
    if (provider.getName() == null || provider.getName().isBlank()) {
      return byId;
    }
    List<Lead> byName = leadRepository.findByAssignedProviderIgnoreCaseOrderByCreatedAtDesc(provider.getName());
    if (byId.isEmpty()) return byName;
    if (byName.isEmpty()) return byId;
    java.util.LinkedHashMap<Long, Lead> merged = new java.util.LinkedHashMap<>();
    for (Lead l : byId) merged.put(l.getId(), l);
    for (Lead l : byName) merged.putIfAbsent(l.getId(), l);
    return new java.util.ArrayList<>(merged.values());
  }

  public Lead requireAssignedLead(Provider provider, Long leadId) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    Long assignedId = lead.getAssignedProviderId();
    if (assignedId != null && assignedId.equals(provider.getId())) {
      return lead;
    }
    String assigned = lead.getAssignedProvider();
    if (assigned != null && assigned.equalsIgnoreCase(provider.getName())) {
      return lead;
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "lead not assigned to this provider");
  }

  public Lead updateLeadStatus(Provider provider, Long leadId, LeadStatus newStatus) {
    return updateLeadStatus(provider, leadId, newStatus, null);
  }

  /**
   * @param amountCharged monto que el proveedor cobró al cliente. Con
   *                       {@code fixy.payments.enabled=true}, es obligatorio
   *                       (> 0) para transicionar a COMPLETED — dispara la
   *                       creación de la comisión (H1.2/H1.3). Con el flag en
   *                       false, se ignora y el comportamiento es el mismo de
   *                       siempre (rollback seguro sin credenciales de MP).
   */
  public Lead updateLeadStatus(Provider provider, Long leadId, LeadStatus newStatus, BigDecimal amountCharged) {
    if (!PROVIDER_TRANSITIONS.contains(newStatus)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "status not allowed for provider self-service");
    }
    if (paymentsEnabled && newStatus == LeadStatus.COMPLETED
        && (amountCharged == null || amountCharged.signum() <= 0)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "para marcar el trabajo como completado necesitamos el monto cobrado al cliente (mayor a 0)");
    }
    Lead lead = requireAssignedLead(provider, leadId);
    LeadStatus before = lead.getStatus();
    if (before != newStatus) {
      lead.setStatus(newStatus);
      provider.setLastRespondedAt(OffsetDateTime.now());
      timelineService.appendEvent(lead, "PROVIDER_STATUS_CHANGE", "provider",
          "%s → %s".formatted(before, newStatus));
      // bumps de contadores en el provider para visibilidad ops
      switch (newStatus) {
        case ASSIGNED -> provider.setAcceptedJobsCount(safeInc(provider.getAcceptedJobsCount()));
        case CANCELLED -> provider.setRejectedJobsCount(safeInc(provider.getRejectedJobsCount()));
        case COMPLETED -> provider.setCompletedJobsCount(safeInc(provider.getCompletedJobsCount()));
        default -> { /* no counter */ }
      }
      leadRepository.save(lead);
      providerRepository.save(provider);
      if (paymentsEnabled && newStatus == LeadStatus.COMPLETED) {
        commissionService.createForCompletedLead(lead, provider, amountCharged);
      }
      if (newStatus == LeadStatus.COMPLETED) {
        // Un solo mensaje: si payments está ON, createForCompletedLead ya
        // mandó el aviso de comisión al proveedor (canal distinto, no
        // pisa este). Este es al cliente, pidiendo confirmación/rating.
        leadClosingService.notifyCustomerOfCompletion(lead);
      }
    }
    return lead;
  }

  /**
   * "Voy en camino" (caso real: Nueva Era escribió "en 40 min maso llega"
   * como texto perdido en el chat, lead #105 — esto lo convierte en una
   * acción de un toque, como Uber). Efectos: evento de timeline
   * PROVIDER_ON_THE_WAY (el cliente lo lee del timeline público existente),
   * mensaje al chat (audience=all, visible para el cliente) y push. Anti-spam:
   * máximo un aviso por hora por lead, chequeado contra el evento más
   * reciente del mismo tipo — evita que un toque doble o un proveedor
   * ansioso spamee al cliente.
   *
   * @param etaMinutes opcional; si viene, se menciona en el mensaje.
   */
  public Lead notifyOnTheWay(Provider provider, Long leadId, Integer etaMinutes) {
    Lead lead = requireAssignedLead(provider, leadId);

    List<LeadEvent> recent = leadEventRepository
        .findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, ON_THE_WAY_EVENT_TYPE);
    if (!recent.isEmpty()) {
      OffsetDateTime lastSentAt = recent.get(0).getCreatedAt();
      if (lastSentAt != null && lastSentAt.isAfter(OffsetDateTime.now().minus(ON_THE_WAY_COOLDOWN))) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
            "ya le avisaste al cliente hace poco; esperá un rato antes de avisar de nuevo");
      }
    }

    String providerName = hasText(provider.getName()) ? provider.getName() : "Tu proveedor";
    String etaSuffix = etaMinutes != null && etaMinutes > 0
        ? " — llega en ~%d min".formatted(etaMinutes)
        : "";
    String message = "🚛 %s avisó que va en camino%s".formatted(providerName, etaSuffix);

    timelineService.appendEvent(lead, ON_THE_WAY_EVENT_TYPE, "provider", message);
    leadMessageService.postFromOps(lead.getId(), "provider", message, "all");
    try {
      pushNotificationService.notifyLeadHasNews(lead.getId(), providerName + " va en camino", message);
    } catch (Exception ex) {
      // best-effort, nunca debe romper el flujo (mismo patrón que el resto de push)
    }
    return lead;
  }

  /**
   * "Horario acordado con un toque" (tanda flujo 2026-07-21): la
   * coordinación de día/hora era 100% chat libre — ni el sistema ni el
   * cliente quedaban con un horario concreto. El proveedor propone desde
   * chips en su panel; la propuesta viaja como evento SCHEDULE_PROPOSED
   * (message = la etiqueta cruda, ej. "mañana de 14 a 16" — el cliente la
   * confirma/rechaza vía {@link LeadScheduleService}) + mensaje de chat +
   * push, mismo trío que "voy en camino".
   *
   * Una nueva propuesta reemplaza a la anterior (el evento más reciente
   * manda) — no hay límite de propuestas, pero sí el anti-doble-toque de
   * 1 min para no duplicar por taps repetidos.
   */
  public Lead proposeSchedule(Provider provider, Long leadId, String rawProposal) {
    Lead lead = requireAssignedLead(provider, leadId);
    String proposal = rawProposal == null ? "" : rawProposal.trim();
    if (proposal.length() < 3 || proposal.length() > 120) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "la propuesta de horario debe tener entre 3 y 120 caracteres");
    }

    List<LeadEvent> recent = leadEventRepository
        .findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, LeadScheduleService.SCHEDULE_PROPOSED_EVENT_TYPE);
    if (!recent.isEmpty()) {
      OffsetDateTime lastSentAt = recent.get(0).getCreatedAt();
      if (lastSentAt != null && lastSentAt.isAfter(OffsetDateTime.now().minusMinutes(1))) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
            "acabás de proponer un horario; esperá un momento antes de proponer otro");
      }
    }

    String providerName = hasText(provider.getName()) ? provider.getName() : "Tu proveedor";
    timelineService.appendEvent(lead, LeadScheduleService.SCHEDULE_PROPOSED_EVENT_TYPE, "provider", proposal);
    leadMessageService.postFromOps(lead.getId(), "provider",
        "📅 %s propone pasar %s. Si te sirve, confirmalo acá en el chat con un toque.".formatted(providerName, proposal),
        "all");
    try {
      pushNotificationService.notifyLeadHasNews(lead.getId(), providerName + " propuso un horario",
          "%s — confirmalo con un toque desde el chat.".formatted(proposal));
    } catch (Exception ex) {
      // best-effort, nunca debe romper el flujo (mismo patrón que el resto de push)
    }
    return lead;
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isBlank();
  }

  /**
   * Disponibilidad MVP (agenda/disponibilidad, base de Ola 2): el proveedor
   * la setea desde su panel con el mismo token de self-service. En pausa
   * ({@code acceptingWork=false}) filtra al proveedor de
   * {@link ProviderOpportunityService#listFor} — no afecta trabajos ya
   * asignados, solo oportunidades nuevas.
   */
  public Provider setAcceptingWork(Provider provider, boolean acceptingWork) {
    provider.setAcceptingWork(acceptingWork);
    return providerRepository.save(provider);
  }

  public Provider regenerateAccessToken(Long providerId) {
    Provider provider = providerRepository.findById(providerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found"));
    provider.setAccessToken(newAccessToken());
    return providerRepository.save(provider);
  }

  /**
   * Devuelve el provider con accessToken garantizado: si ya tiene uno, lo
   * deja intacto (no rota el link que ops ya pudo haber compartido); si es
   * null, genera uno nuevo. Usado por avisos automáticos (Telegram) que
   * necesitan el link de panel sin invalidar tokens ya entregados.
   */
  /**
   * Resumen público del proveedor asignado a un lead, para
   * {@code LeadResponse.assignedProviderSummary} (contrato acordado con el
   * agente que construye la superficie del cliente). Null si el lead no
   * tiene proveedor asignado o el proveedor no existe más. Honestidad de
   * reputación: {@code ratingAverage} null si {@code ratingCount == 0},
   * mismo criterio que {@link ProviderCatalogService#publicPreview}.
   */
  public LeadResponse.AssignedProviderSummary summaryForAssignedProvider(Lead lead) {
    if (lead == null || lead.getAssignedProviderId() == null) {
      return null;
    }
    Provider provider = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
    if (provider == null) {
      return null;
    }
    int ratingCount = provider.getRatingCount() == null ? 0 : provider.getRatingCount();
    Double ratingAverage = ratingCount == 0 ? null : provider.getRatingAverage();
    return new LeadResponse.AssignedProviderSummary(
        provider.getName(),
        ratingAverage,
        ratingCount,
        provider.getCompletedJobsCount(),
        provider.getPrimaryZone()
    );
  }

  public Provider ensureAccessToken(Long providerId) {
    Provider provider = providerRepository.findById(providerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found"));
    if (provider.getAccessToken() == null || provider.getAccessToken().isBlank()) {
      provider.setAccessToken(newAccessToken());
      return providerRepository.save(provider);
    }
    return provider;
  }

  private String newAccessToken() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private int safeInc(Integer value) {
    return value == null ? 1 : value + 1;
  }

  /**
   * "Mi perfil" (self-service, Ola 2): SOLO estos campos son editables por
   * el proveedor. Deliberadamente no toca status, rating, comisiones ni
   * categories — eso rompe matching y lo gestiona ops. Validación de
   * tamaños ya la hace {@code @Valid} en el controller; acá solo se
   * defiende contra blank tras trim (Bean Validation no lo hace por sí
   * solo con {@code @NotBlank} si el string trae solo espacios raros, pero
   * esto es belt-and-suspenders).
   */
  public Provider updateProfile(
      Provider provider,
      String name,
      String description,
      String coverageZones,
      String phone
  ) {
    String trimmedName = name == null ? "" : name.trim();
    if (trimmedName.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "el nombre no puede estar vacío");
    }
    provider.setName(trimmedName);
    provider.setDescription(blankToNull(description));
    provider.setCoverageZones(blankToNull(coverageZones));
    if (hasText(phone)) {
      provider.setPhone(phone.trim());
    }
    return providerRepository.save(provider);
  }

  private String blankToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * "Mis números" (self-service, Ola 2): estadísticas derivadas de datos
   * que ya existen, sin tabla nueva. Tasa de aceptación = aceptados /
   * (aceptados + rechazados) sobre los contadores que
   * {@link #updateLeadStatus} ya viene incrementando desde que existe el
   * panel de proveedor — null si todavía no hay ninguno de los dos (nunca
   * 0% dramático para un proveedor nuevo). Completados por semana: últimas
   * 4 semanas (lunes a lunes), fechado por el evento de timeline
   * PROVIDER_STATUS_CHANGE "→ COMPLETED" más reciente de cada lead
   * asignado — el mismo patrón documentado en
   * {@code LeadEventRepository.findByLeadIdInOrderByLeadIdAscCreatedAtAsc}
   * porque {@code lead.updatedAt} se pisa con cualquier cambio posterior
   * (ej. un mensaje de chat después de completar).
   */
  public ProviderStatsResponse statsFor(Provider provider) {
    Integer accepted = provider.getAcceptedJobsCount();
    Integer rejected = provider.getRejectedJobsCount();
    int acceptedCount = accepted == null ? 0 : accepted;
    int rejectedCount = rejected == null ? 0 : rejected;
    int totalDecisions = acceptedCount + rejectedCount;
    Double acceptanceRate = totalDecisions == 0 ? null : (double) acceptedCount / totalDecisions;

    int ratingCount = provider.getRatingCount() == null ? 0 : provider.getRatingCount();
    Double ratingAverage = ratingCount == 0 ? null : provider.getRatingAverage();

    List<ProviderStatsResponse.WeeklyCompleted> completedByWeek = completedByWeek(provider);

    return new ProviderStatsResponse(
        acceptanceRate,
        acceptedCount,
        rejectedCount,
        ratingAverage,
        ratingCount,
        completedByWeek
    );
  }

  private List<ProviderStatsResponse.WeeklyCompleted> completedByWeek(Provider provider) {
    List<Lead> leads = assignedLeadsFor(provider);
    List<Long> completedLeadIds = leads.stream()
        .filter(lead -> lead.getStatus() == LeadStatus.COMPLETED)
        .map(Lead::getId)
        .toList();

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    java.time.LocalDate currentWeekStart = now.toLocalDate().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));

    // 4 semanas, más vieja primero, para que el mini-gráfico del front no
    // tenga que reordenar.
    List<java.time.LocalDate> weekStarts = new ArrayList<>();
    for (int i = 3; i >= 0; i--) {
      weekStarts.add(currentWeekStart.minusWeeks(i));
    }
    Map<java.time.LocalDate, Integer> counts = new LinkedHashMap<>();
    for (java.time.LocalDate ws : weekStarts) counts.put(ws, 0);

    if (!completedLeadIds.isEmpty()) {
      List<LeadEvent> events = leadEventRepository.findByLeadIdInOrderByLeadIdAscCreatedAtAsc(completedLeadIds);
      // Último evento "→ COMPLETED" por lead (un lead puede completarse,
      // reabrirse y completarse de nuevo en teoría; nos quedamos con el
      // más reciente).
      Map<Long, OffsetDateTime> completedAtByLead = new LinkedHashMap<>();
      for (LeadEvent event : events) {
        if ("PROVIDER_STATUS_CHANGE".equals(event.getType()) && event.getMessage() != null
            && event.getMessage().endsWith("→ COMPLETED")) {
          completedAtByLead.put(event.getLead().getId(), event.getCreatedAt());
        }
      }
      java.time.LocalDate windowStart = weekStarts.get(0);
      for (OffsetDateTime completedAt : completedAtByLead.values()) {
        if (completedAt == null) continue;
        java.time.LocalDate completedDate = completedAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        if (completedDate.isBefore(windowStart)) continue;
        java.time.LocalDate weekStart = completedDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        counts.computeIfPresent(weekStart, (k, v) -> v + 1);
      }
    }

    List<ProviderStatsResponse.WeeklyCompleted> result = new ArrayList<>();
    for (java.time.LocalDate ws : weekStarts) {
      result.add(new ProviderStatsResponse.WeeklyCompleted(ws.toString(), counts.get(ws)));
    }
    return result;
  }
}
