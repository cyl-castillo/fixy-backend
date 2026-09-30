package com.fixy.backend.service;

import com.fixy.backend.domain.DomainCatalog;
import com.fixy.backend.dto.DiscoveredProviderCreateRequest;
import com.fixy.backend.dto.DiscoveredProviderLinkResponse;
import com.fixy.backend.dto.IntakeRequest;
import com.fixy.backend.dto.IntakeResponse;
import com.fixy.backend.dto.LeadCreateRequest;
import com.fixy.backend.dto.LeadMatchResponse;
import com.fixy.backend.dto.LeadResponse;
import com.fixy.backend.dto.LeadUpdateRequest;
import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.dto.ProviderCreateRequest;
import com.fixy.backend.dto.ProviderMatchItem;
import com.fixy.backend.dto.ProviderResponse;
import com.fixy.backend.dto.PublicLeadContextUpdateRequest;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeadService {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LeadService.class);
  private static final DateTimeFormatter HISTORY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  /** Fuente única: DomainCatalog (domain/home-services.yml). */
  private static final Set<String> MVP_CATEGORIES = Set.copyOf(DomainCatalog.get().mvpIds());
  // Las zonas cubiertas ya no viven acá: fuente única en
  // com.fixy.backend.model.CoverageZone (se consulta con isCovered, que
  // normaliza acentos). Esta lista estaba escrita a mano y se había quedado
  // sin "Montes de Solymar" — agregada el 2026-07-16 solo en AgentService y
  // LeadAgentService —, así que un pedido en esa zona salía marcado
  // zona_fuera_de_cobertura mientras el agente lo daba por matcheable.

  private final LeadRepository leadRepository;
  private final AgentService agentService;
  private final ProviderCatalogService providerCatalogService;
  private final LeadTimelineService leadTimelineService;
  private final LeadAgentService leadAgentService;
  private final TelegramNotifyService telegramNotifyService;
  private final PushNotificationService pushNotificationService;
  private final ProviderSelfService providerSelfService;
  private final com.fixy.backend.repository.ServiceCatalogItemRepository serviceCatalogItemRepository;
  private final com.fixy.backend.repository.CustomerPaymentRepository customerPaymentRepository;
  private final com.fixy.backend.repository.LeadEventRepository leadEventRepository;
  private final com.fixy.backend.repository.LeadRatingRepository leadRatingRepository;
  private final LeadMessageService leadMessageService;
  private final SearchDeadlineService searchDeadlineService;
  private final com.fixy.backend.repository.ProviderLeadDeclineRepository declineRepository;
  private final com.fixy.backend.repository.ProviderOfferRepository providerOfferRepository;

  public LeadService(
      LeadRepository leadRepository,
      AgentService agentService,
      ProviderCatalogService providerCatalogService,
      LeadTimelineService leadTimelineService,
      LeadAgentService leadAgentService,
      TelegramNotifyService telegramNotifyService,
      PushNotificationService pushNotificationService,
      // @Lazy: mismo motivo que en TelegramNotifyService — providerSelfService
      // solo se usa en runtime (ensureAccessToken) y evita ciclo de beans.
      @org.springframework.context.annotation.Lazy ProviderSelfService providerSelfService,
      com.fixy.backend.repository.ServiceCatalogItemRepository serviceCatalogItemRepository,
      com.fixy.backend.repository.CustomerPaymentRepository customerPaymentRepository,
      com.fixy.backend.repository.LeadEventRepository leadEventRepository,
      com.fixy.backend.repository.LeadRatingRepository leadRatingRepository,
      LeadMessageService leadMessageService,
      SearchDeadlineService searchDeadlineService,
      com.fixy.backend.repository.ProviderLeadDeclineRepository declineRepository,
      com.fixy.backend.repository.ProviderOfferRepository providerOfferRepository
  ) {
    this.leadRepository = leadRepository;
    this.agentService = agentService;
    this.providerCatalogService = providerCatalogService;
    this.leadTimelineService = leadTimelineService;
    this.leadAgentService = leadAgentService;
    this.telegramNotifyService = telegramNotifyService;
    this.pushNotificationService = pushNotificationService;
    this.providerSelfService = providerSelfService;
    this.serviceCatalogItemRepository = serviceCatalogItemRepository;
    this.customerPaymentRepository = customerPaymentRepository;
    this.leadEventRepository = leadEventRepository;
    this.leadRatingRepository = leadRatingRepository;
    this.leadMessageService = leadMessageService;
    this.searchDeadlineService = searchDeadlineService;
    this.declineRepository = declineRepository;
    this.providerOfferRepository = providerOfferRepository;
  }

  /**
   * Crea un Lead en estado "draft" para chat-first. El cliente todavia no
   * proporciono ni problema ni datos — la conversacion va a ir extrayendo
   * categoria, zona, urgencia y telefono a lo largo de los turnos.
   */
  public LeadResponse createChat(com.fixy.backend.dto.PublicChatStartRequest request) {
    Lead lead = new Lead();
    lead.setName(request != null ? request.name() : null);
    lead.setPhone(request != null ? request.phone() : null);
    lead.setProblem("(pendiente)");
    lead.setChannel(request != null && hasText(request.channel()) ? request.channel() : "chat");
    // CTA "Pedir por Fixy" (FIXY_OFERTAS_CTA_DESIGN.md §3.2): sin validar
    // contra el catálogo de ofertas acá — es solo un dato de atribución
    // para medir conversión oferta→lead (OfferResponse.leadCount).
    lead.setSourceOfferId(request != null ? request.sourceOfferId() : null);
    lead.setDetectedCategory(null);
    lead.setUrgency(null);
    lead.setLocation(null);
    lead.setSummary(null);
    lead.setMissingFields("");
    lead.setReadyForMatching(false);
    lead.setStatus(LeadStatus.NEW);
    lead.setNotes("");
    lead.setHistory(buildHistoryEntry("Chat-first iniciado desde %s".formatted(lead.getChannel())));
    lead.setAccessToken(java.util.UUID.randomUUID().toString().replace("-", ""));

    Lead saved = leadRepository.save(lead);
    leadTimelineService.appendEvent(saved, "CHAT_STARTED", "user", "Lead vacio iniciado desde chat-first");
    try {
      leadAgentService.greet(saved);
    } catch (Exception ex) {
      // greet is best-effort; never block lead creation.
    }
    return toResponse(saved, null, "chat");
  }

  public LeadResponse create(LeadCreateRequest request) {
    IntakeResponse classification = classify(request);

    Lead lead = new Lead();
    lead.setName(request.name());
    lead.setPhone(request.phone());
    lead.setProblem(request.problem());
    lead.setChannel(request.channel());
    applyClassification(lead, classification);
    lead.setStatus(LeadStatus.NEW);
    lead.setNotes("");
    lead.setHistory(buildHistoryEntry("Lead creado desde %s".formatted(safe(request.channel()))));
    lead.setAccessToken(java.util.UUID.randomUUID().toString().replace("-", ""));

    Lead saved = leadRepository.save(lead);
    leadTimelineService.appendEvent(saved, "LEAD_CREATED", "user", "Lead creado desde %s".formatted(safe(request.channel())));
    leadTimelineService.appendEvent(saved, "INTAKE_CLASSIFIED", "agent", "Categoría %s | urgencia %s | siguiente paso %s"
        .formatted(safe(saved.getDetectedCategory()), safe(saved.getUrgency()), nextRecommendedAction(computeBlockingFields(saved))));
    try {
      leadAgentService.greet(saved);
    } catch (Exception ex) {
      // greet is best-effort; never block lead creation.
    }
    return toResponse(saved, classification.suggestedReply(), classification.agentSource());
  }

  public List<LeadResponse> list(String status) {
    List<Lead> leads = (status == null || status.isBlank())
        ? leadRepository.findAllByOrderByCreatedAtDesc()
        : leadRepository.findByStatusOrderByCreatedAtDesc(parseStatus(status));

    return leads.stream()
        .map(lead -> toResponse(lead, null, null))
        .toList();
  }

  public LeadResponse get(Long id) {
    Lead lead = findLead(id);
    return toResponse(lead, null, null);
  }

  /**
   * Valida el token público del lead (mismo patrón que LeadMessageService /
   * LeadClosingService: token del LEAD, no del proveedor). Los endpoints
   * públicos por-lead DEBEN pasar por acá antes de leer o mutar: sin esto,
   * cualquiera que itere IDs ve/edita datos de contacto ajenos (IDOR).
   */
  public void requirePublicToken(Long id, String token) {
    Lead lead = findLead(id);
    if (lead.getAccessToken() == null || token == null || !lead.getAccessToken().equals(token)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid token");
    }
  }

  /**
   * Tier 2 (contrato §B.4): {@code POST /api/public/leads/{id}/time-window}
   * — el vecino cambia la franja mientras sigue esperando técnico. Solo si
   * todavía no hay técnico asignado (ASSIGNED+); reinicia la hora límite de
   * búsqueda y dispara un reintento en el acto (el contactado que no
   * respondía queda como TIMEOUT y se re-ofrece a otro, vía el mismo camino
   * del watchdog).
   */
  public LeadResponse changeTimeWindow(Long leadId, String token, String timeWindowId) {
    requirePublicToken(leadId, token);
    Lead lead = findLead(leadId);
    if (lead.getAssignedProviderId() != null && lead.getStatus() != LeadStatus.PROVIDER_CONTACTED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "ya hay un técnico asignado, no se puede cambiar la franja desde acá");
    }
    var orderTimeWindow = com.fixy.backend.model.OrderTimeWindow.fromId(timeWindowId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "ventana horaria inválida"));

    boolean wasContacted = lead.getStatus() == LeadStatus.PROVIDER_CONTACTED;
    lead.setTimeWindow(orderTimeWindow.id());
    lead.setUrgency(orderTimeWindow.urgency());
    searchDeadlineService.begin(lead);
    leadRepository.save(lead);

    leadTimelineService.appendEvent(lead, "TIME_WINDOW_CHANGED", "customer",
        "Cambió la franja a %s".formatted(orderTimeWindow.label()));
    leadMessageService.postFromOps(lead.getId(), "fixy",
        "Listo, lo cambié a **%s**. Sigo buscando hasta las %s."
            .formatted(orderTimeWindow.label(), searchDeadlineService.formatHHmm(lead.getSearchDeadlineAt())));

    if (wasContacted && lead.getAssignedProviderId() != null) {
      // El contactado que no había respondido queda como TIMEOUT (mismo
      // criterio que MatchingWatchdogScheduler.handleRelease) y se re-ofrece
      // a otro en el acto — findMatchesForLead necesita el decline
      // registrado para no volver a ofrecérselo al mismo.
      Long unresponsiveProviderId = lead.getAssignedProviderId();
      if (!declineRepository.existsByLeadIdAndProviderId(lead.getId(), unresponsiveProviderId)) {
        var decline = new com.fixy.backend.model.ProviderLeadDecline();
        decline.setLeadId(lead.getId());
        decline.setProviderId(unresponsiveProviderId);
        declineRepository.save(decline);
      }
      providerOfferRepository
          .findFirstByLeadIdAndProviderIdAndRespondedAtIsNullOrderByOfferedAtDesc(lead.getId(), unresponsiveProviderId)
          .ifPresent(offer -> {
            offer.setRespondedAt(java.time.OffsetDateTime.now());
            offer.setResponse(com.fixy.backend.model.ProviderOfferResponse.TIMEOUT);
            providerOfferRepository.save(offer);
          });
      try {
        leadAgentService.reofferAfterDecline(lead.getId());
      } catch (Exception ex) {
        log.warn("changeTimeWindow: re-oferta falló para el lead {}: {}", lead.getId(), ex.getMessage());
      }
    } else {
      try {
        leadAgentService.retryAutoMatch(lead.getId());
      } catch (Exception ex) {
        log.warn("changeTimeWindow: reintento de matching falló para el lead {}: {}", lead.getId(), ex.getMessage());
      }
    }

    return toResponse(leadRepository.findById(leadId).orElseThrow(), null, null);
  }

  public LeadMatchResponse generateMatches(Long id) {
    Lead lead = findLead(id);
    // Si el lead ya viene clasificado (chat-first agentico, o lead enriquecido por updateContext)
    // NO re-clasificar: respetar lo que ya tenemos para no perder datos. Solo (re)clasificar
    // cuando todavia no hay categoria detectada confiable.
    boolean alreadyClassified = hasText(lead.getDetectedCategory())
        && !"otro".equalsIgnoreCase(lead.getDetectedCategory())
        && hasText(lead.getLocation())
        && !"sin definir".equalsIgnoreCase(lead.getLocation());
    if (!alreadyClassified) {
      IntakeResponse classification = classify(buildClassificationMessage(lead), lead.getName(), lead.getPhone(), lead.getChannel());
      applyClassification(lead, classification);
    } else {
      // Aun asi recomputar campos derivados (missingFields, readyForMatching) en base al estado actual.
      lead.setReadyForMatching(computeBlockingFields(lead).isEmpty());
    }

    List<String> blockingFields = computeBlockingFields(lead);

    if (!blockingFields.isEmpty()) {
      String message = "Matching bloqueado por campos faltantes: " + String.join(", ", blockingFields);
      lead.setHistory(appendHistory(lead.getHistory(), message));
      Lead saved = leadRepository.save(lead);
      leadTimelineService.appendEvent(saved, "MATCH_BLOCKED", "system", message);
      return new LeadMatchResponse(
          toResponse(saved, null, null),
          List.of(),
          blockingFields,
          nextRecommendedAction(blockingFields)
      );
    }

    // Para lead concreto: no reofrece a quien ya rechazó ESTE pedido, mismo
    // criterio que la bandeja del proveedor y que el matching automático.
    List<ProviderCatalogItem> catalogMatches = providerCatalogService.findMatchesForLead(
        lead.getId(), lead.getDetectedCategory(), lead.getLocation());
    List<ProviderMatchItem> matches = catalogMatches.stream()
        .map(provider -> toProviderMatch(provider, lead))
        .sorted((a, b) -> Integer.compare(b.score(), a.score()))
        .toList();

    String message = "Matching generado: %d proveedor(es)".formatted(matches.size());
    lead.setHistory(appendHistory(lead.getHistory(), message));
    Lead saved = leadRepository.save(lead);
    leadTimelineService.appendEvent(saved, "MATCH_GENERATED", "system", message);
    notifyOpsOfOpportunity(saved, catalogMatches);

    return new LeadMatchResponse(
        toResponse(saved, null, null),
        matches,
        List.of(),
        matches.isEmpty() ? "ampliar_busqueda_o_handoff" : "present_matches"
    );
  }

  public DiscoveredProviderLinkResponse createDiscoveredProvider(Long leadId, DiscoveredProviderCreateRequest request) {
    Lead lead = findLead(leadId);

    String categories = hasText(request.categories()) ? request.categories().trim() : safe(lead.getDetectedCategory());
    String primaryZone = hasText(request.primaryZone()) ? request.primaryZone().trim() : safe(lead.getLocation());
    String city = hasText(request.city()) ? request.city().trim() : "Ciudad de la Costa";
    String notes = mergeNotes(request.notes(), "Proveedor descubierto desde lead #" + lead.getId());

    ProviderResponse provider = providerCatalogService.create(new ProviderCreateRequest(
        request.name(),
        request.phone(),
        request.whatsappNumber(),
        request.sourceName(),
        "web_discovered",
        primaryZone,
        request.coverageZones(),
        city,
        request.department(),
        categories,
        request.categoryNotes(),
        notes
    ));

    boolean assignedToLead = request.assignToLead();
    String message;

    if (assignedToLead) {
      lead.setAssignedProvider(provider.name());
      lead.setHistory(appendHistory(lead.getHistory(), "Proveedor descubierto y asignado: " + provider.name()));
      Lead saved = leadRepository.save(lead);
      leadTimelineService.appendEvent(saved, "DISCOVERED_PROVIDER_LINKED", "ops", "Proveedor descubierto y asignado: " + provider.name());
      message = "Proveedor descubierto creado y asignado al lead.";
      return new DiscoveredProviderLinkResponse(provider, toResponse(saved, null, null), true, message);
    }

    lead.setHistory(appendHistory(lead.getHistory(), "Proveedor descubierto registrado: " + provider.name()));
    Lead saved = leadRepository.save(lead);
    leadTimelineService.appendEvent(saved, "DISCOVERED_PROVIDER_REGISTERED", "ops", "Proveedor descubierto registrado: " + provider.name());
    message = "Proveedor descubierto creado y vinculado al contexto del lead.";
    return new DiscoveredProviderLinkResponse(provider, toResponse(saved, null, null), false, message);
  }

  public LeadResponse updatePublicContext(Long id, PublicLeadContextUpdateRequest request) {
    Lead lead = findLead(id);
    List<String> changes = new ArrayList<>();

    if (hasText(request.problem()) && !Objects.equals(request.problem(), lead.getProblem())) {
      lead.setProblem(request.problem().trim());
      changes.add("Problema actualizado");
    }
    if (hasText(request.name()) && !Objects.equals(request.name(), lead.getName())) {
      lead.setName(request.name().trim());
      changes.add("Nombre actualizado");
    }
    if (hasText(request.phone()) && !Objects.equals(request.phone(), lead.getPhone())) {
      lead.setPhone(request.phone().trim());
      changes.add("Telefono actualizado");
    }
    if (hasText(request.channel()) && !Objects.equals(request.channel(), lead.getChannel())) {
      lead.setChannel(request.channel().trim());
      changes.add("Canal actualizado");
    }
    if (hasText(request.location()) && !Objects.equals(request.location(), lead.getLocation())) {
      lead.setLocation(request.location().trim());
      changes.add("Ubicacion agregada");
    }
    if (hasText(request.notes())) {
      String mergedNotes = mergeNotes(lead.getNotes(), request.notes().trim());
      if (!Objects.equals(mergedNotes, lead.getNotes())) {
        lead.setNotes(mergedNotes);
        changes.add("Contexto adicional agregado");
      }
    }
    if (hasText(request.address())) {
      String mergedNotes = mergeNotes(lead.getNotes(), "Dirección: " + request.address().trim());
      if (!Objects.equals(mergedNotes, lead.getNotes())) {
        lead.setNotes(mergedNotes);
        changes.add("Dirección agregada");
      }
    }
    if (hasText(request.details())) {
      String mergedNotes = mergeNotes(lead.getNotes(), request.details().trim());
      if (!Objects.equals(mergedNotes, lead.getNotes())) {
        lead.setNotes(mergedNotes);
        changes.add("Detalle agregado");
      }
    }
    if (hasText(request.serviceCategory())) {
      changes.add("Servicio sugerido: " + request.serviceCategory().trim());
    }
    if (hasText(request.urgency())) {
      changes.add("Urgencia sugerida: " + request.urgency().trim());
    }

    if (changes.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no public context changes provided");
    }

    IntakeResponse classification = agentService.classify(new IntakeRequest(
        buildClassificationMessage(lead),
        lead.getName(),
        lead.getPhone(),
        lead.getChannel(),
        request.serviceCategory(),
        request.location(),
        request.urgency(),
        request.address(),
        request.details()
    ));
    applyClassification(lead, classification);
    String historyMessage = "Contexto publico enriquecido | " + String.join(" | ", changes);
    lead.setHistory(appendHistory(lead.getHistory(), historyMessage));

    Lead saved = leadRepository.save(lead);
    leadTimelineService.appendEvent(saved, "CONTEXT_UPDATED", "user", String.join(" | ", changes));
    leadTimelineService.appendEvent(saved, "INTAKE_CLASSIFIED", "agent", "Categoría %s | urgencia %s | siguiente paso %s"
        .formatted(safe(saved.getDetectedCategory()), safe(saved.getUrgency()), nextRecommendedAction(computeBlockingFields(saved))));
    return toResponse(saved, classification.suggestedReply(), classification.agentSource());
  }

  public LeadResponse update(Long id, LeadUpdateRequest request) {
    Lead lead = findLead(id);

    List<String> changes = new ArrayList<>();

    if (request.status() != null && request.status() != lead.getStatus()) {
      changes.add("Estado: %s → %s".formatted(lead.getStatus(), request.status()));
      lead.setStatus(request.status());
    }

    // assignedProviderId tiene precedencia sobre assignedProvider (string).
    // Cuando viene el ID, resolvemos al provider y seteamos ambos campos
    // para mantener compatibilidad con leads viejos que sólo tienen nombre.
    if (request.assignedProviderId() != null) {
      Long newId = request.assignedProviderId();
      Long currentId = lead.getAssignedProviderId();
      if (!Objects.equals(newId, currentId)) {
        String resolvedName = providerCatalogService.get(newId).name();
        String before = safe(lead.getAssignedProvider()).isBlank() ? "sin asignar" : lead.getAssignedProvider();
        changes.add("Proveedor: %s → %s (#%d)".formatted(before, resolvedName, newId));
        lead.setAssignedProviderId(newId);
        lead.setAssignedProvider(resolvedName);
      }
    } else if (request.assignedProvider() != null
        && !Objects.equals(request.assignedProvider(), lead.getAssignedProvider())) {
      String before = safe(lead.getAssignedProvider()).isBlank() ? "sin asignar" : lead.getAssignedProvider();
      String after = safe(request.assignedProvider()).isBlank() ? "sin asignar" : request.assignedProvider();
      changes.add("Proveedor: %s → %s".formatted(before, after));
      lead.setAssignedProvider(request.assignedProvider());
      // No tocamos assignedProviderId: el caller eligió un string sin ID.
    }

    if (request.notes() != null && !Objects.equals(request.notes(), lead.getNotes())) {
      changes.add("Notas actualizadas");
      lead.setNotes(request.notes());
    }

    if (!changes.isEmpty()) {
      String message = String.join(" | ", changes);
      lead.setHistory(appendHistory(lead.getHistory(), message));
      Lead saved = leadRepository.save(lead);
      leadTimelineService.appendEvent(saved, "LEAD_UPDATED", "ops", message);
      return toResponse(saved, null, null);
    }

    Lead saved = leadRepository.save(lead);
    return toResponse(saved, null, null);
  }

  private Lead findLead(Long id) {
    return leadRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
  }

  private IntakeResponse classify(String message, String name, String phone, String channel) {
    return agentService.classify(new IntakeRequest(message, name, phone, channel));
  }

  private IntakeResponse classify(LeadCreateRequest request) {
    return agentService.classify(new IntakeRequest(
        request.problem(),
        request.name(),
        request.phone(),
        request.channel(),
        request.serviceCategory(),
        request.zone(),
        request.urgency(),
        request.address(),
        request.details()
    ));
  }

  private void applyClassification(Lead lead, IntakeResponse classification) {
    lead.setDetectedCategory(classification.serviceCategory());
    lead.setUrgency(classification.urgency());
    if (!"sin definir".equalsIgnoreCase(classification.area())) {
      lead.setLocation(classification.area());
    }
    lead.setSummary(classification.summary());
    lead.setMissingFields(serializeMissingFields(normalizeMissingFields(classification.missingFields(), lead)));
    lead.setReadyForMatching(computeBlockingFields(lead).isEmpty());
  }

  private List<String> normalizeMissingFields(List<String> missingFields, Lead lead) {
    return missingFields.stream()
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .filter(value -> !("zona".equalsIgnoreCase(value) && hasText(lead.getLocation())
            && !"sin definir".equalsIgnoreCase(lead.getLocation())))
        .filter(value -> !("categoria".equalsIgnoreCase(value) && hasText(lead.getDetectedCategory())
            && !"otro".equalsIgnoreCase(lead.getDetectedCategory())))
        .distinct()
        .toList();
  }

  private List<String> computeBlockingFields(Lead lead) {
    List<String> blockingFields = new ArrayList<>();
    String category = normalize(lead.getDetectedCategory());
    String location = normalize(lead.getLocation());

    if (category.isBlank() || "otro".equals(category)) {
      blockingFields.add("categoria");
    } else if (!MVP_CATEGORIES.contains(category)) {
      blockingFields.add("categoria_fuera_de_alcance");
    }

    if (location.isBlank() || "sin definir".equals(location)) {
      blockingFields.add("zona");
    } else if (!DomainCatalog.get().isCovered(location)) {
      blockingFields.add("zona_fuera_de_cobertura");
    }
    return blockingFields;
  }

  private String nextRecommendedAction(List<String> blockingFields) {
    if (blockingFields.contains("categoria_fuera_de_alcance")) {
      return "out_of_scope_category";
    }
    if (blockingFields.contains("zona_fuera_de_cobertura")) {
      return "out_of_coverage_area";
    }
    if (blockingFields.contains("categoria")) {
      return "ask_service_category";
    }
    if (blockingFields.contains("zona")) {
      return "ask_location";
    }
    return "generate_matches";
  }

  /**
   * Aviso a Carlos por Telegram (parche interino hasta credenciales Meta de
   * proveedor). notifyOpportunityWithMatches/notifyDemandWithoutSupply son
   * @Async y ya tienen catch-all interno; este try/catch es una segunda red
   * de seguridad para que un fallo nunca tumbe la respuesta del matching.
   */
  private void notifyOpsOfOpportunity(Lead lead, List<ProviderCatalogItem> matches) {
    try {
      if (matches.isEmpty()) {
        telegramNotifyService.notifyDemandWithoutSupply(lead);
      } else {
        telegramNotifyService.notifyOpportunityWithMatches(lead, matches);
      }
    } catch (Exception ex) {
      // best-effort, nunca romper generateMatches
    }
    notifyProvidersOfOpportunity(lead, matches);
  }

  /**
   * Web Push (Ola UX): oportunidad nueva → aviso directo a cada proveedor
   * que matchea, si se suscribió. No-op silencioso si push no está
   * configurado. Best-effort por proveedor: que uno falle no afecta a los
   * demás ni al flujo de generateMatches.
   */
  private void notifyProvidersOfOpportunity(Lead lead, List<ProviderCatalogItem> matches) {
    if (!pushNotificationService.isEnabled()) return;
    for (ProviderCatalogItem match : matches) {
      try {
        com.fixy.backend.model.Provider provider = providerSelfService.ensureAccessToken(match.id());
        pushNotificationService.notifyProvider(provider.getId(), provider.getAccessToken(),
            "Nueva oportunidad para vos",
            humanCategory(lead.getDetectedCategory()) + " en " + safe(lead.getLocation()));
      } catch (Exception ex) {
        // best-effort, nunca romper generateMatches
      }
    }
  }

  private String humanCategory(String raw) {
    return raw == null || raw.isBlank() ? "servicio" : raw;
  }

  private ProviderMatchItem toProviderMatch(ProviderCatalogItem provider, Lead lead) {
    List<String> reasons = new ArrayList<>();
    int score = 0;

    if (equalsNormalized(provider.category(), lead.getDetectedCategory())) {
      score += 50;
      reasons.add("categoria_coincide");
    }

    if (equalsNormalized(provider.zone(), lead.getLocation())) {
      score += 30;
      reasons.add("zona_coincide");
    } else if (isSameCityFallback(provider.zone(), lead.getLocation())) {
      score += 10;
      reasons.add("cobertura_ciudad");
    }

    if ("AVAILABLE".equalsIgnoreCase(provider.status())) {
      score += 20;
      reasons.add("disponible");
    }

    return new ProviderMatchItem(
        provider.id(),
        provider.name(),
        provider.category(),
        provider.zone(),
        provider.phone(),
        score,
        reasons,
        provider.status(),
        provider.sourceType()
    );
  }

  private boolean isSameCityFallback(String providerZone, String leadLocation) {
    return "ciudad de la costa".equals(normalize(providerZone)) && !normalize(leadLocation).isBlank();
  }

  private String buildClassificationMessage(Lead lead) {
    List<String> parts = new ArrayList<>();
    if (hasText(lead.getProblem())) {
      parts.add(lead.getProblem().trim());
    }
    if (hasText(lead.getLocation())) {
      parts.add("Zona: " + lead.getLocation().trim());
    }
    if (hasText(lead.getNotes())) {
      parts.add("Contexto: " + lead.getNotes().trim());
    }
    return String.join(". ", parts);
  }

  private String serializeMissingFields(List<String> missingFields) {
    return String.join("||", missingFields);
  }

  private List<String> deserializeMissingFields(String raw) {
    if (!hasText(raw)) {
      return List.of();
    }
    return Arrays.stream(raw.split("\\|\\|"))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList();
  }

  private String mergeNotes(String currentNotes, String extraNotes) {
    if (!hasText(currentNotes)) {
      return extraNotes;
    }
    if (!hasText(extraNotes) || currentNotes.contains(extraNotes)) {
      return currentNotes;
    }
    return currentNotes + "\n" + extraNotes;
  }

  private LeadStatus parseStatus(String raw) {
    try {
      return LeadStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status");
    }
  }

  private String buildHistoryEntry(String message) {
    return "%s · %s".formatted(OffsetDateTime.now().format(HISTORY_FORMAT), message);
  }

  private String appendHistory(String history, String message) {
    String entry = buildHistoryEntry(message);
    if (history == null || history.isBlank()) {
      return entry;
    }
    return history + "\n" + entry;
  }

  private LeadResponse toResponse(Lead lead, String suggestedReply, String agentSource) {
    List<String> missingFields = deserializeMissingFields(lead.getMissingFields());
    List<String> blockingFields = computeBlockingFields(lead);

    String serviceName = null;
    Integer servicePriceFrom = null;
    if (hasText(lead.getServiceCode())) {
      // findByCode (no findByCodeAndActiveTrue): un pedido viejo tiene que
      // seguir mostrando qué se pidió aunque el servicio se haya desactivado
      // después — desactivar no debe borrar la ficha de pedidos ya hechos.
      var service = serviceCatalogItemRepository.findByCode(lead.getServiceCode()).orElse(null);
      if (service != null) {
        serviceName = service.getName();
        servicePriceFrom = service.getPriceFrom();
      }
    }
    LeadResponse.OnSiteContact onSiteContact = hasText(lead.getOnSiteContactName()) || hasText(lead.getOnSiteContactPhone())
        ? new LeadResponse.OnSiteContact(lead.getOnSiteContactName(), lead.getOnSiteContactPhone())
        : null;

    LeadResponse.ServiceFee serviceFee = customerPaymentRepository.findByLeadId(lead.getId())
        .filter(p -> p.getKind() == com.fixy.backend.model.CustomerPaymentKind.SERVICE_FEE)
        .map(p -> new LeadResponse.ServiceFee(p.getAmount(), p.getStatus(), p.getMpPaymentLink(), p.getGuaranteeUntil()))
        .orElse(null);

    LeadResponse.PriceChange priceChange = lead.getProposedAt() == null ? null : new LeadResponse.PriceChange(
        lead.getProposedAmount(),
        lead.getProposedReason(),
        lead.getProposedAt(),
        lead.getAgreedAmount(),
        lead.getAgreedAt(),
        com.fixy.backend.model.PriceChangeStatus.of(lead)
    );

    // Tier 2 (contrato §B.3): null en cuanto hay técnico asignado — la
    // promesa "sigo buscando hasta las HH:mm" deja de tener sentido.
    java.util.Set<com.fixy.backend.model.LeadStatus> hasProviderStatuses = java.util.Set.of(
        com.fixy.backend.model.LeadStatus.ASSIGNED, com.fixy.backend.model.LeadStatus.IN_PROGRESS,
        com.fixy.backend.model.LeadStatus.COMPLETED, com.fixy.backend.model.LeadStatus.CANCELLED);
    OffsetDateTime searchDeadlineAt = hasProviderStatuses.contains(lead.getStatus()) ? null : lead.getSearchDeadlineAt();
    String matchingState = matchingStateFor(lead, hasProviderStatuses);

    LeadResponse.Rating rating = leadRatingRepository.findByLeadId(lead.getId())
        .map(r -> new LeadResponse.Rating(
            r.getScore(), r.getComment(), r.isVerified(), r.getCreatedAt(), r.getProviderReply(), r.getProviderReplyAt()))
        .orElse(null);

    return new LeadResponse(
        lead.getId(),
        lead.getName(),
        lead.getPhone(),
        lead.getProblem(),
        lead.getDetectedCategory(),
        lead.getUrgency(),
        lead.getLocation(),
        lead.getSummary(),
        missingFields,
        blockingFields,
        blockingFields.isEmpty(),
        nextRecommendedAction(blockingFields),
        lead.getAssignedProvider(),
        lead.getNotes(),
        lead.getHistory(),
        lead.getStatus(),
        suggestedReply,
        agentSource,
        lead.getAccessToken(),
        lead.getCreatedAt(),
        lead.getUpdatedAt(),
        lead.isDisputed(),
        lead.getDisputeResolvedAt(),
        lead.getDisputeResolutionNote(),
        providerSelfService.summaryForAssignedProvider(lead),
        lead.getServiceCode(),
        serviceName,
        servicePriceFrom,
        lead.getTimeWindow(),
        lead.isRemote(),
        onSiteContact,
        serviceFee,
        priceChange,
        searchDeadlineAt,
        matchingState,
        rating
    );
  }

  /**
   * Tier 2 (contrato §B.3): {@code SEARCHING} (listo, sin contactar aún),
   * {@code CONTACTED} (status {@code PROVIDER_CONTACTED}), {@code
   * NO_PROVIDER} (hubo un {@code MATCH_BLOCKED} posterior al último {@code
   * PROVIDER_CONTACTED} — o directamente sin contacto — o venció la hora
   * límite), {@code null} en el resto (todavía no listo, o ya hay técnico).
   */
  private String matchingStateFor(Lead lead, java.util.Set<LeadStatus> hasProviderStatuses) {
    if (lead.getId() == null || hasProviderStatuses.contains(lead.getStatus()) || !lead.isReadyForMatching()) {
      return null;
    }
    OffsetDateTime lastContacted = lastEventAt(lead.getId(), "PROVIDER_CONTACTED");
    OffsetDateTime lastMatchBlocked = lastEventAt(lead.getId(), "MATCH_BLOCKED");
    boolean matchBlockedIsRecent = lastMatchBlocked != null
        && (lastContacted == null || lastMatchBlocked.isAfter(lastContacted));
    // Igual que MATCH_BLOCKED: la hora límite vencida solo cuenta si es
    // POSTERIOR al último contacto y el deadline vigente ya venció. Si el
    // vecino cambió la franja (deadline nuevo, futuro) o se re-ofreció a otro
    // técnico después del aviso, el estado vuelve a CONTACTED/SEARCHING.
    OffsetDateTime lastDeadlineMissed = lastEventAt(lead.getId(), "SEARCH_DEADLINE_MISSED");
    boolean deadlineStillMissed = lastDeadlineMissed != null
        && (lastContacted == null || lastDeadlineMissed.isAfter(lastContacted))
        && (lead.getSearchDeadlineAt() == null || !lead.getSearchDeadlineAt().isAfter(OffsetDateTime.now()));
    if (matchBlockedIsRecent || deadlineStillMissed) {
      return "NO_PROVIDER";
    }
    if (lead.getStatus() == LeadStatus.PROVIDER_CONTACTED) {
      return "CONTACTED";
    }
    return "SEARCHING";
  }

  private OffsetDateTime lastEventAt(Long leadId, String type) {
    return leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, type).stream()
        .map(com.fixy.backend.model.LeadEvent::getCreatedAt)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private boolean equalsNormalized(String left, String right) {
    return normalize(left).equals(normalize(right));
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isBlank();
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }
}
