package com.fixy.backend.service;

import com.fixy.backend.dto.IntakeRequest;
import com.fixy.backend.dto.IntakeResponse;
import com.fixy.backend.dto.LeadCreateRequest;
import com.fixy.backend.dto.LeadResponse;
import com.fixy.backend.dto.LeadUpdateRequest;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeadService {

  private static final DateTimeFormatter HISTORY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final LeadRepository leadRepository;
  private final AgentService agentService;

  public LeadService(LeadRepository leadRepository, AgentService agentService) {
    this.leadRepository = leadRepository;
    this.agentService = agentService;
  }

  public LeadResponse create(LeadCreateRequest request) {
    IntakeResponse classification = agentService.classify(new IntakeRequest(
        request.problem(),
        request.name(),
        request.phone(),
        request.channel()
    ));

    Lead lead = new Lead();
    lead.setName(request.name());
    lead.setPhone(request.phone());
    lead.setProblem(request.problem());
    lead.setChannel(request.channel());
    lead.setDetectedCategory(classification.serviceCategory());
    lead.setUrgency(classification.urgency());
    lead.setLocation(classification.area());
    lead.setStatus(LeadStatus.NEW);
    lead.setNotes("");
    lead.setHistory(buildHistoryEntry("Lead creado desde %s".formatted(safe(request.channel()))));

    Lead saved = leadRepository.save(lead);
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

  public LeadResponse update(Long id, LeadUpdateRequest request) {
    Lead lead = leadRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));

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
      lead.setHistory(appendHistory(lead.getHistory(), String.join(" | ", changes)));
    }

    Lead saved = leadRepository.save(lead);
    return toResponse(saved, null, null);
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
    return new LeadResponse(
        lead.getId(),
        lead.getName(),
        lead.getPhone(),
        lead.getProblem(),
        lead.getDetectedCategory(),
        lead.getUrgency(),
        lead.getLocation(),
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

  private String safe(String value) {
    return value == null ? "" : value;
  }
}
