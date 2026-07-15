package com.fixy.backend.controller;

import com.fixy.backend.dto.LinkLeadRequest;
import com.fixy.backend.dto.UserLeadSummary;
import com.fixy.backend.model.Lead;
import com.fixy.backend.service.AuthService;
import com.fixy.backend.service.UserLeadService;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Mis pedidos": vincular leads anónimos ya creados (posesión del
 * accessToken prueba propiedad) y listarlos. Bajo /api/public/** (permitAll
 * en SecurityConfig) — la seguridad acá es el Bearer del session token,
 * verificado a mano por AuthService.requireUser, no un filter chain nuevo.
 */
@RestController
@RequestMapping("/api/public/me")
public class PublicMeController {

  private final AuthService authService;
  private final UserLeadService userLeadService;

  public PublicMeController(AuthService authService, UserLeadService userLeadService) {
    this.authService = authService;
    this.userLeadService = userLeadService;
  }

  @PostMapping("/leads")
  @ResponseStatus(HttpStatus.OK)
  public void linkLead(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody LinkLeadRequest request
  ) {
    Long userId = authService.requireUser(authorization);
    userLeadService.linkLead(userId, request.leadId(), request.accessToken());
  }

  @GetMapping("/leads")
  public List<UserLeadSummary> myLeads(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
  ) {
    Long userId = authService.requireUser(authorization);
    return userLeadService.listLeads(userId).stream()
        .map(this::toSummary)
        .toList();
  }

  private UserLeadSummary toSummary(Lead lead) {
    return new UserLeadSummary(
        lead.getId(),
        lead.getDetectedCategory(),
        lead.getLocation(),
        lead.getStatus(),
        lead.getUrgency(),
        lead.getCreatedAt(),
        lead.getSummary(),
        lead.getAccessToken(),
        lead.isDisputed(),
        lead.getDisputeResolvedAt()
    );
  }
}
