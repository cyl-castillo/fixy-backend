package com.fixy.backend.controller;

import com.fixy.backend.dto.LinkLeadRequest;
import com.fixy.backend.dto.UserLeadSummary;
import com.fixy.backend.model.AppUser;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.Lead;
import com.fixy.backend.service.AuthService;
import com.fixy.backend.service.BusinessService;
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
 * accessToken prueba propiedad) y listarlos. También descubre si la sesión
 * del chat es dueña de un comercio (Fase 3 del panel del dueño, ver
 * {@code /merchant}). Bajo /api/public/** (permitAll en SecurityConfig) — la
 * seguridad acá es el Bearer del session token, verificado a mano por
 * AuthService.requireUser, no un filter chain nuevo.
 */
@RestController
@RequestMapping("/api/public/me")
public class PublicMeController {

  private final AuthService authService;
  private final UserLeadService userLeadService;
  private final BusinessService businessService;

  public PublicMeController(
      AuthService authService, UserLeadService userLeadService, BusinessService businessService
  ) {
    this.authService = authService;
    this.userLeadService = userLeadService;
    this.businessService = businessService;
  }

  /**
   * Descubre desde la sesión del chat si el usuario logueado es dueño de un
   * comercio (Fase 3 del panel del dueño): el googleSub del AppUser de la
   * sesión se busca contra {@code Business.googleSub} (vinculado en Fase 1
   * vía el link mágico del panel). 404 si la cuenta no tiene comercio
   * vinculado — el frontend no muestra el acceso al panel. El panelToken
   * viene garantizado (lazy, sin rotar) vía {@code BusinessService.ensurePanel}.
   */
  @GetMapping("/merchant")
  public MerchantAccountResponse myMerchant(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
  ) {
    AppUser user = authService.requireUserEntity(authorization);
    Business business = businessService.findLinkedByGoogleSub(user.getGoogleSub());
    return new MerchantAccountResponse(business.getId(), business.getName(), business.getPanelToken());
  }

  public record MerchantAccountResponse(Long businessId, String name, String panelToken) {
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
