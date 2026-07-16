package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadMessageCreateRequest;
import com.fixy.backend.dto.LeadMessageResponse;
import com.fixy.backend.dto.ProviderAssignedLeadSummary;
import com.fixy.backend.dto.ProviderCommissionSummary;
import com.fixy.backend.dto.ProviderOpportunitySummary;
import com.fixy.backend.dto.ProviderSelfResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.service.LeadMessageService;
import com.fixy.backend.service.LeadPaymentQueryService;
import com.fixy.backend.service.ProviderOpportunityService;
import com.fixy.backend.service.ProviderSelfService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
  private final ProviderOpportunityService opportunityService;
  private final LeadPaymentQueryService leadPaymentQueryService;

  public PublicProviderSelfController(
      ProviderSelfService selfService,
      LeadMessageService messageService,
      ProviderOpportunityService opportunityService,
      LeadPaymentQueryService leadPaymentQueryService
  ) {
    this.selfService = selfService;
    this.messageService = messageService;
    this.opportunityService = opportunityService;
    this.leadPaymentQueryService = leadPaymentQueryService;
  }

  @GetMapping("/opportunities")
  public List<ProviderOpportunitySummary> opportunities(
      @PathVariable Long providerId,
      @RequestParam("token") String token
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    return opportunityService.listFor(provider);
  }

  @PostMapping("/opportunities/{leadId}/accept")
  public ProviderAssignedLeadSummary acceptOpportunity(
      @PathVariable Long providerId,
      @PathVariable Long leadId,
      @RequestParam("token") String token
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    return opportunityService.accept(provider, leadId);
  }

  @PostMapping("/opportunities/{leadId}/decline")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void declineOpportunity(
      @PathVariable Long providerId,
      @PathVariable Long leadId,
      @RequestParam("token") String token
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    opportunityService.decline(provider, leadId);
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

  @PatchMapping("/availability")
  public ProviderSelfResponse updateAvailability(
      @PathVariable Long providerId,
      @RequestParam("token") String token,
      @Valid @RequestBody AvailabilityUpdateRequest request
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    Provider updated = selfService.setAcceptingWork(provider, request.acceptingWork());
    List<ProviderAssignedLeadSummary> leads = selfService.assignedLeadsFor(updated).stream()
        .map(ProviderAssignedLeadSummary::fromEntity)
        .toList();
    return ProviderSelfResponse.fromEntity(updated, leads);
  }

  @GetMapping("/commissions")
  public ProviderCommissionSummary commissions(
      @PathVariable Long providerId,
      @RequestParam("token") String token
  ) {
    selfService.authenticate(providerId, token);
    return leadPaymentQueryService.summaryFor(providerId);
  }

  @PostMapping("/leads/{leadId}/status")
  public ProviderAssignedLeadSummary updateStatus(
      @PathVariable Long providerId,
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @Valid @RequestBody StatusUpdateRequest request
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    Lead updated = selfService.updateLeadStatus(provider, leadId, request.status(), request.amountCharged());
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
      return messageService.listSinceForProvider(lead.getId(), lead.getAccessToken(), sinceId);
    }
    return messageService.listForProvider(lead.getId(), lead.getAccessToken());
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

  public record StatusUpdateRequest(@NotNull LeadStatus status, BigDecimal amountCharged) {
  }

  public record AvailabilityUpdateRequest(@NotNull Boolean acceptingWork) {
  }
}
