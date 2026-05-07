package com.fixy.backend.service;

import com.fixy.backend.dto.LeadMessageResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeadMessageService {

  private static final Set<String> PUBLIC_SENDERS = Set.of("customer");
  private static final Set<String> PROVIDER_SENDERS = Set.of("provider", "fixy");
  private static final int MAX_TEXT_LENGTH = 2000;

  private final LeadMessageRepository messageRepository;
  private final LeadRepository leadRepository;
  private final LeadTimelineService timelineService;

  public LeadMessageService(
      LeadMessageRepository messageRepository,
      LeadRepository leadRepository,
      LeadTimelineService timelineService
  ) {
    this.messageRepository = messageRepository;
    this.leadRepository = leadRepository;
    this.timelineService = timelineService;
  }

  public List<LeadMessageResponse> listForCustomer(Long leadId, String token) {
    Lead lead = requireLeadAndToken(leadId, token);
    return messageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()).stream()
        .map(LeadMessageResponse::fromEntity)
        .toList();
  }

  public List<LeadMessageResponse> listSinceForCustomer(Long leadId, String token, Long sinceId) {
    Lead lead = requireLeadAndToken(leadId, token);
    return messageRepository
        .findByLeadIdAndIdGreaterThanOrderByCreatedAtAsc(lead.getId(), sinceId)
        .stream()
        .map(LeadMessageResponse::fromEntity)
        .toList();
  }

  public LeadMessageResponse postFromCustomer(Long leadId, String token, String rawText) {
    Lead lead = requireLeadAndToken(leadId, token);
    String text = sanitize(rawText);
    LeadMessage saved = persist(lead.getId(), "customer", text);
    timelineService.appendEvent(lead, "MESSAGE_FROM_CUSTOMER", "user",
        text.length() > 80 ? text.substring(0, 80) + "…" : text);
    return LeadMessageResponse.fromEntity(saved);
  }

  public List<LeadMessageResponse> listForOps(Long leadId) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    return messageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()).stream()
        .map(LeadMessageResponse::fromEntity)
        .toList();
  }

  public LeadMessageResponse postFromOps(Long leadId, String sender, String rawText) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    if (!PROVIDER_SENDERS.contains(sender)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sender must be one of " + PROVIDER_SENDERS);
    }
    String text = sanitize(rawText);
    LeadMessage saved = persist(lead.getId(), sender, text);
    String eventType = sender.equals("provider") ? "MESSAGE_FROM_PROVIDER" : "MESSAGE_FROM_FIXY";
    timelineService.appendEvent(lead, eventType, sender,
        text.length() > 80 ? text.substring(0, 80) + "…" : text);
    return LeadMessageResponse.fromEntity(saved);
  }

  private Lead requireLeadAndToken(Long leadId, String token) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    if (lead.getAccessToken() == null || token == null || !lead.getAccessToken().equals(token)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid token");
    }
    return lead;
  }

  private LeadMessage persist(Long leadId, String sender, String text) {
    if (!PUBLIC_SENDERS.contains(sender) && !PROVIDER_SENDERS.contains(sender)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid sender");
    }
    LeadMessage message = new LeadMessage();
    message.setLeadId(leadId);
    message.setSender(sender);
    message.setText(text);
    return messageRepository.save(message);
  }

  private String sanitize(String text) {
    if (text == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
    }
    String trimmed = text.trim();
    if (trimmed.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
    }
    if (trimmed.length() > MAX_TEXT_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text exceeds max length");
    }
    return trimmed;
  }
}
