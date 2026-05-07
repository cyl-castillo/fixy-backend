package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadMessageCreateRequest;
import com.fixy.backend.dto.LeadMessageResponse;
import com.fixy.backend.dto.ProviderAssignedLeadSummary;
import com.fixy.backend.dto.ProviderSelfResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.service.LeadMessageService;
import com.fixy.backend.service.ProviderSelfService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
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
@RequestMapping("/api/public/providers/{providerId}")
public class PublicProviderSelfController {

  private final ProviderSelfService selfService;
  private final LeadMessageService messageService;

  public PublicProviderSelfController(
      ProviderSelfService selfService,
      LeadMessageService messageService
  ) {
    this.selfService = selfService;
    this.messageService = messageService;
  }

  @GetMapping("/me")
  public ProviderSelfResponse me(
      @PathVariable Long providerId,
      @RequestParam("token") String token
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    List<ProviderAssignedLeadSummary> leads = selfService.assignedLeadsFor(provider).stream()
        .map(ProviderAssignedLeadSummary::fromEntity)
        .toList();
    return ProviderSelfResponse.fromEntity(provider, leads);
  }

  @PostMapping("/leads/{leadId}/status")
  public ProviderAssignedLeadSummary updateStatus(
      @PathVariable Long providerId,
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @Valid @RequestBody StatusUpdateRequest request
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    Lead updated = selfService.updateLeadStatus(provider, leadId, request.status());
    return ProviderAssignedLeadSummary.fromEntity(updated);
  }

  @GetMapping("/leads/{leadId}/messages")
  public List<LeadMessageResponse> listMessages(
      @PathVariable Long providerId,
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @RequestParam(value = "since", required = false) Long sinceId
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    Lead lead = selfService.requireAssignedLead(provider, leadId);
    if (sinceId != null) {
      return messageService.listSinceForCustomer(lead.getId(), lead.getAccessToken(), sinceId);
    }
    return messageService.listForCustomer(lead.getId(), lead.getAccessToken());
  }

  @PostMapping("/leads/{leadId}/messages")
  @ResponseStatus(HttpStatus.CREATED)
  public LeadMessageResponse postMessage(
      @PathVariable Long providerId,
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @Valid @RequestBody LeadMessageCreateRequest request
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    selfService.requireAssignedLead(provider, leadId);
    // El proveedor manda mensaje vía LeadMessageService.postFromOps con sender=provider.
    return messageService.postFromOps(leadId, "provider", request.text());
  }

  public record StatusUpdateRequest(@NotNull LeadStatus status) {
  }
}
