package com.fixy.backend.service;

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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeadService {

  private static final DateTimeFormatter HISTORY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final LeadRepository leadRepository;
  private final AgentService agentService;
  private final ProviderCatalogService providerCatalogService;
  private final LeadTimelineService leadTimelineService;

  public LeadService(
      LeadRepository leadRepository,
      AgentService agentService,
      ProviderCatalogService providerCatalogService,
      LeadTimelineService leadTimelineService
  ) {
    this.leadRepository = leadRepository;
    this.agentService = agentService;
    this.providerCatalogService = providerCatalogService;
    this.leadTimelineService = leadTimelineService;
  }

  public LeadResponse create(LeadCreateRequest request) {
    IntakeResponse classification = classify(request.problem(), request.name(), request.phone(), request.channel());

    Lead lead = new Lead();
    lead.setName(request.name());
    lead.setPhone(request.phone());
    lead.setProblem(request.problem());
    lead.setChannel(request.channel());
    applyClassification(lead, classification);
    lead.setStatus(LeadStatus.NEW);
    lead.setNotes("");
    lead.setHistory(buildHistoryEntry("Lead creado desde %s".formatted(safe(request.channel()))));

    Lead saved = leadRepository.save(lead);
    leadTimelineService.appendEvent(saved, "LEAD_CREATED", "user", "Lead creado desde %s".formatted(safe(request.channel())));
    leadTimelineService.appendEvent(saved, "INTAKE_CLASSIFIED", "agent", "Categoría %s | urgencia %s | siguiente paso %s"
        .formatted(safe(saved.getDetectedCategory()), safe(saved.getUrgency()), nextRecommendedAction(computeBlockingFields(saved))));
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

  public LeadMatchResponse generateMatches(Long id) {
    Lead lead = findLead(id);
    IntakeResponse classification = classify(buildClassificationMessage(lead), lead.getName(), lead.getPhone(), lead.getChannel());
    applyClassification(lead, classification);

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

    List<ProviderMatchItem> matches = providerCatalogService.findMatches(lead.getDetectedCategory(), lead.getLocation()).stream()
        .map(provider -> toProviderMatch(provider, lead))
        .sorted((a, b) -> Integer.compare(b.score(), a.score()))
        .toList();

    String message = "Matching generado: %d proveedor(es)".formatted(matches.size());
    lead.setHistory(appendHistory(lead.getHistory(), message));
    Lead saved = leadRepository.save(lead);
    leadTimelineService.appendEvent(saved, "MATCH_GENERATED", "system", message);

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

    if (changes.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no public context changes provided");
    }

    IntakeResponse classification = classify(buildClassificationMessage(lead), lead.getName(), lead.getPhone(), lead.getChannel());
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

    if (request.assignedProvider() != null && !Objects.equals(request.assignedProvider(), lead.getAssignedProvider())) {
      String before = safe(lead.getAssignedProvider()).isBlank() ? "sin asignar" : lead.getAssignedProvider();
      String after = safe(request.assignedProvider()).isBlank() ? "sin asignar" : request.assignedProvider();
      changes.add("Proveedor: %s → %s".formatted(before, after));
      lead.setAssignedProvider(request.assignedProvider());
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

  private void applyClassification(Lead lead, IntakeResponse classification) {
    lead.setDetectedCategory(classification.serviceCategory());
    lead.setUrgency(classification.urgency());
    if (!"sin definir".equalsIgnoreCase(classification.area())) {
      lead.setLocation(classification.area());
    }
    lead.setSummary(classification.summary());
    lead.setMissingFields(serializeMissingFields(classification.missingFields()));
    lead.setReadyForMatching(computeBlockingFields(lead).isEmpty());
  }

  private List<String> computeBlockingFields(Lead lead) {
    List<String> blockingFields = new ArrayList<>();
    if (!hasText(lead.getDetectedCategory()) || "otro".equalsIgnoreCase(lead.getDetectedCategory())) {
      blockingFields.add("categoria");
    }
    if (!hasText(lead.getLocation()) || "sin definir".equalsIgnoreCase(lead.getLocation())) {
      blockingFields.add("zona");
    }
    return blockingFields;
  }

  private String nextRecommendedAction(List<String> blockingFields) {
    if (blockingFields.contains("categoria")) {
      return "ask_service_category";
    }
    if (blockingFields.contains("zona")) {
      return "ask_location";
    }
    return "generate_matches";
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
        lead.getCreatedAt(),
        lead.getUpdatedAt()
    );
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
