package com.fixy.backend.service;

import com.fixy.backend.dto.IntakeRequest;
import com.fixy.backend.dto.IntakeResponse;
import com.fixy.backend.dto.ProviderLeadCreateRequest;
import com.fixy.backend.dto.ProviderLeadResponse;
import com.fixy.backend.model.ProviderLead;
import com.fixy.backend.model.ProviderLeadStatus;
import com.fixy.backend.repository.ProviderLeadRepository;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProviderLeadService {

  private final ProviderLeadRepository providerLeadRepository;
  private final AgentService agentService;

  public ProviderLeadService(ProviderLeadRepository providerLeadRepository, AgentService agentService) {
    this.providerLeadRepository = providerLeadRepository;
    this.agentService = agentService;
  }

  public ProviderLeadResponse create(ProviderLeadCreateRequest request) {
    IntakeResponse classification = agentService.classify(new IntakeRequest(
        request.message(),
        request.name(),
        request.phone(),
        request.channel()
    ));

    ProviderLead providerLead = new ProviderLead();
    providerLead.setName(request.name());
    providerLead.setPhone(request.phone());
    providerLead.setMessage(request.message());
    providerLead.setChannel(request.channel());
    providerLead.setCategory(classification.serviceCategory());
    providerLead.setZone(normalizeArea(classification.area()));
    providerLead.setUrgency(classification.urgency());
    providerLead.setSummary(classification.summary());
    providerLead.setMissingFields("");
    providerLead.setReadyForReview(computeMissingFields(providerLead).isEmpty());
    providerLead.setStatus(providerLead.isReadyForReview() ? ProviderLeadStatus.READY_FOR_REVIEW : ProviderLeadStatus.PENDING_REVIEW);
    providerLead.setNotes("");

    ProviderLead saved = providerLeadRepository.save(providerLead);
    return toResponse(saved, classification.suggestedReply(), classification.agentSource());
  }

  private ProviderLeadResponse toResponse(ProviderLead providerLead, String suggestedReply, String agentSource) {
    List<String> missingFields = computeMissingFields(providerLead);
    return new ProviderLeadResponse(
        providerLead.getId(),
        providerLead.getName(),
        providerLead.getPhone(),
        providerLead.getMessage(),
        providerLead.getCategory(),
        providerLead.getZone(),
        providerLead.getUrgency(),
        providerLead.getSummary(),
        missingFields,
        providerLead.isReadyForReview(),
        missingFields.isEmpty() ? "ready_for_review" : "complete_provider_profile",
        providerLead.getStatus(),
        providerLead.isReadyForReview() || suggestedReply == null || suggestedReply.isBlank()
            ? defaultReply(providerLead, missingFields)
            : suggestedReply,
        agentSource,
        providerLead.getCreatedAt(),
        providerLead.getUpdatedAt()
    );
  }

  private List<String> computeMissingFields(ProviderLead providerLead) {
    List<String> current = providerLead.getMissingFields() == null || providerLead.getMissingFields().isBlank()
        ? new java.util.ArrayList<>()
        : new java.util.ArrayList<>(Arrays.stream(providerLead.getMissingFields().split("\\|"))
            .filter(value -> value != null && !value.isBlank())
            .toList());

    if (providerLead.getZone() == null || providerLead.getZone().isBlank() || "sin definir".equalsIgnoreCase(providerLead.getZone())) {
      if (!current.contains("zona")) current.add("zona");
    }
    if (providerLead.getCategory() == null || providerLead.getCategory().isBlank() || "otro".equalsIgnoreCase(providerLead.getCategory())) {
      if (!current.contains("rubro")) current.add("rubro");
    }
    if (providerLead.getPhone() == null || providerLead.getPhone().isBlank()) {
      if (!current.contains("telefono")) current.add("telefono");
    }
    return current.stream().distinct().toList();
  }

  private String normalizeArea(String area) {
    return "sin definir".equalsIgnoreCase(area) ? null : area;
  }

  private String defaultReply(ProviderLead providerLead, List<String> missingFields) {
    if (missingFields.isEmpty()) {
      return "Perfecto. Ya tengo suficiente para dejar tu perfil listo para revisión interna.";
    }
    return "Gracias. Ya entendí bastante de tu perfil, pero todavía necesito: " + String.join(", ", missingFields) + ".";
  }
}
