package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadMessageCreateRequest;
import com.fixy.backend.dto.LeadMessageResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.LeadAgentService;
import com.fixy.backend.service.LeadMessageService;
import com.fixy.backend.service.WhatsAppService;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/leads/{leadId}/messages")
public class PublicLeadMessageController {

  private static final Logger log = LoggerFactory.getLogger(PublicLeadMessageController.class);

  private final LeadMessageService messageService;
  private final LeadAgentService agentService;
  private final LeadRepository leadRepository;
  private final ProviderRepository providerRepository;
  private final WhatsAppService whatsappService;

  public PublicLeadMessageController(
      LeadMessageService messageService,
      LeadAgentService agentService,
      LeadRepository leadRepository,
      ProviderRepository providerRepository,
      WhatsAppService whatsappService
  ) {
    this.messageService = messageService;
    this.agentService = agentService;
    this.leadRepository = leadRepository;
    this.providerRepository = providerRepository;
    this.whatsappService = whatsappService;
  }

  @GetMapping
  public List<LeadMessageResponse> list(
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @RequestParam(value = "since", required = false) Long sinceId
  ) {
    if (sinceId != null) {
      return messageService.listSinceForCustomer(leadId, token, sinceId);
    }
    return messageService.listForCustomer(leadId, token);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public LeadMessageResponse create(
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @Valid @RequestBody LeadMessageCreateRequest request
  ) {
    LeadMessageResponse persisted = messageService.postFromCustomer(leadId, token, request.text());
    // Si el lead ya está asignado a un proveedor, la conversación es entre
    // humanos: el agente NO responde (aunque WhatsApp esté apagado — antes el
    // else lo hacía interrumpir la charla cliente↔proveedor con enlatados).
    // Si además WhatsApp está habilitado, hacemos relay del mensaje del
    // cliente directo al WhatsApp del proveedor.
    Lead lead = leadRepository.findById(leadId).orElse(null);
    boolean assignedToProvider = lead != null
        && lead.getAssignedProviderId() != null
        && (lead.getStatus() == LeadStatus.ASSIGNED
            || lead.getStatus() == LeadStatus.IN_PROGRESS
            || lead.getStatus() == LeadStatus.PROVIDER_CONTACTED);
    if (assignedToProvider) {
      if (whatsappService.isEnabled()) {
        try {
          Provider p = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
          if (p != null) {
            String to = (p.getWhatsappNumber() == null || p.getWhatsappNumber().isBlank())
                ? p.getPhone() : p.getWhatsappNumber();
            if (to != null && !to.isBlank()) {
              String customerName = lead.getName() == null ? "Cliente" : lead.getName();
              whatsappService.sendText(to, customerName + " (lead #" + lead.getId() + "): " + request.text());
            }
          }
        } catch (Exception ex) {
          log.warn("relay customer→provider failed: {}", ex.getMessage());
        }
      }
    } else {
      agentService.respondToCustomerAsync(leadId);
    }
    return persisted;
  }
}
