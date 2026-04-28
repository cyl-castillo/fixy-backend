package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadCreateRequest;
import com.fixy.backend.dto.LeadEventResponse;
import com.fixy.backend.dto.LeadMatchResponse;
import com.fixy.backend.dto.LeadResponse;
import com.fixy.backend.dto.PublicLeadContextUpdateRequest;
import com.fixy.backend.service.LeadService;
import com.fixy.backend.service.LeadTimelineService;
import com.fixy.backend.service.PublicLeadAbuseProtectionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicLeadController {

  private final LeadService leadService;
  private final LeadTimelineService leadTimelineService;
  private final PublicLeadAbuseProtectionService abuseProtectionService;

  public PublicLeadController(
      LeadService leadService,
      LeadTimelineService leadTimelineService,
      PublicLeadAbuseProtectionService abuseProtectionService
  ) {
    this.leadService = leadService;
    this.leadTimelineService = leadTimelineService;
    this.abuseProtectionService = abuseProtectionService;
  }

  @PostMapping("/leads")
  @ResponseStatus(HttpStatus.CREATED)
  public LeadResponse create(@Valid @RequestBody LeadCreateRequest request, HttpServletRequest httpRequest) {
    abuseProtectionService.validate(httpRequest.getRemoteAddr(), request.problem());
    return leadService.create(request);
  }

  @GetMapping("/leads/{id}")
  public LeadResponse get(@PathVariable Long id) {
    return leadService.get(id);
  }

  @GetMapping("/leads/{id}/timeline")
  public List<LeadEventResponse> timeline(@PathVariable Long id) {
    leadService.get(id);
    return leadTimelineService.listForLead(id);
  }

  @PatchMapping("/leads/{id}/context")
  public LeadResponse updateContext(
      @PathVariable Long id,
      @RequestBody PublicLeadContextUpdateRequest request,
      HttpServletRequest httpRequest
  ) {
    abuseProtectionService.validateContextUpdate(
        httpRequest.getRemoteAddr(),
        request.problem(),
        request.notes(),
        request.location()
    );
    return leadService.updatePublicContext(id, request);
  }

  @PostMapping("/leads/{id}/matches")
  public LeadMatchResponse generateMatches(@PathVariable Long id) {
    return leadService.generateMatches(id);
  }
}
