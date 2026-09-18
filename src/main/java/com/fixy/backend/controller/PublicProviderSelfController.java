package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadMessageCreateRequest;
import com.fixy.backend.dto.LeadMessageResponse;
import com.fixy.backend.dto.LeadRatingReplyRequest;
import com.fixy.backend.dto.LeadResponse;
import com.fixy.backend.dto.PriceChangeProposeRequest;
import com.fixy.backend.dto.ProviderAssignedLeadSummary;
import com.fixy.backend.dto.ProviderCommissionSummary;
import com.fixy.backend.dto.ProviderOpportunitySummary;
import com.fixy.backend.dto.ProviderProfileUpdateRequest;
import com.fixy.backend.dto.ProviderSelfResponse;
import com.fixy.backend.dto.ProviderStatsResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadRatingRepository;
import com.fixy.backend.service.LeadClosingService;
import com.fixy.backend.service.LeadMessageService;
import com.fixy.backend.service.LeadPaymentQueryService;
import com.fixy.backend.service.PriceChangeService;
import com.fixy.backend.service.ProviderOpportunityService;
import com.fixy.backend.service.ProviderGoogleAuthService;
import com.fixy.backend.service.ProviderPhotoService;
import com.fixy.backend.service.ProviderSelfService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/public/providers/{providerId}")
public class PublicProviderSelfController {

  private final ProviderSelfService selfService;
  private final LeadMessageService messageService;
  private final ProviderOpportunityService opportunityService;
  private final LeadPaymentQueryService leadPaymentQueryService;
  private final ProviderGoogleAuthService providerGoogleAuthService;
  private final com.fixy.backend.service.LeadAgentService leadAgentService;
  private final PriceChangeService priceChangeService;
  private final ProviderPhotoService providerPhotoService;
  private final LeadClosingService leadClosingService;
  private final LeadRatingRepository leadRatingRepository;

  public PublicProviderSelfController(
      ProviderSelfService selfService,
      LeadMessageService messageService,
      ProviderOpportunityService opportunityService,
      LeadPaymentQueryService leadPaymentQueryService,
      ProviderGoogleAuthService providerGoogleAuthService,
      com.fixy.backend.service.LeadAgentService leadAgentService,
      PriceChangeService priceChangeService,
      ProviderPhotoService providerPhotoService,
      LeadClosingService leadClosingService,
      LeadRatingRepository leadRatingRepository
  ) {
    this.selfService = selfService;
    this.messageService = messageService;
    this.opportunityService = opportunityService;
    this.leadPaymentQueryService = leadPaymentQueryService;
    this.providerGoogleAuthService = providerGoogleAuthService;
    this.leadAgentService = leadAgentService;
    this.priceChangeService = priceChangeService;
    this.providerPhotoService = providerPhotoService;
    this.leadClosingService = leadClosingService;
    this.leadRatingRepository = leadRatingRepository;
  }

  private List<ProviderAssignedLeadSummary> assignedLeadSummaries(Provider provider) {
    return selfService.assignedLeadsFor(provider).stream()
        .map(lead -> ProviderAssignedLeadSummary.fromEntity(lead, leadRatingRepository.findByLeadId(lead.getId()).orElse(null)))
        .toList();
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
      @RequestParam("token") String token,
      @RequestBody(required = false) AcceptOpportunityRequest request
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    String arrivalWindow = request == null ? null : request.arrivalWindow();
    return opportunityService.accept(provider, leadId, arrivalWindow);
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
    return ProviderSelfResponse.fromEntity(provider, assignedLeadSummaries(provider), selfService.declinedLeadsFor(provider));
  }

  /**
   * Tier 2 (contrato §A.4): {@code acceptingWork} y/o {@code
   * availabilityWindows}, al menos uno presente (400 si ninguno).
   */
  @PatchMapping("/availability")
  public ProviderSelfResponse updateAvailability(
      @PathVariable Long providerId,
      @RequestParam("token") String token,
      @RequestBody AvailabilityUpdateRequest request
  ) {
    if (request.acceptingWork() == null && request.availabilityWindows() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "mandá acceptingWork y/o availabilityWindows");
    }
    Provider provider = selfService.authenticate(providerId, token);
    Provider updated = selfService.updateAvailability(provider, request.acceptingWork(), request.availabilityWindows());
    return ProviderSelfResponse.fromEntity(updated, assignedLeadSummaries(updated));
  }

  /**
   * "Mi perfil" (self-service, Ola 2): edita SOLO nombre, descripción,
   * zonas de cobertura y teléfono. Nunca status, rating, comisiones ni
   * categories — eso lo gestiona ops porque cambiar categorías rompe
   * matching. {@link ProviderProfileUpdateRequest} no tiene esos campos,
   * así que ni deserializándolos a mano se pueden colar.
   */
  @PatchMapping("/profile")
  public ProviderSelfResponse updateProfile(
      @PathVariable Long providerId,
      @RequestParam("token") String token,
      @Valid @RequestBody ProviderProfileUpdateRequest request
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    Provider updated = selfService.updateProfile(
        provider,
        request.name(),
        request.description(),
        request.coverageZones(),
        request.phone()
    );
    return ProviderSelfResponse.fromEntity(updated, assignedLeadSummaries(updated));
  }

  /** Tier 2 (contrato §B.1): "Tu foto" — el vecino la ve cuando aceptás su pedido. */
  @PostMapping("/photo")
  public ProviderSelfResponse uploadPhoto(
      @PathVariable Long providerId,
      @RequestParam("token") String token,
      @RequestParam("file") MultipartFile file
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    Provider updated = providerPhotoService.upload(provider, file);
    return ProviderSelfResponse.fromEntity(updated, assignedLeadSummaries(updated));
  }

  @DeleteMapping("/photo")
  public ProviderSelfResponse removePhoto(
      @PathVariable Long providerId,
      @RequestParam("token") String token
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    Provider updated = providerPhotoService.remove(provider);
    return ProviderSelfResponse.fromEntity(updated, assignedLeadSummaries(updated));
  }

  /** Tier 2 (contrato §C.3): respuesta pública del proveedor a la reseña de este trabajo. */
  @PostMapping("/leads/{leadId}/rating-reply")
  public LeadResponse.Rating replyToRating(
      @PathVariable Long providerId,
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @Valid @RequestBody LeadRatingReplyRequest request
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    return leadClosingService.replyToRating(provider, leadId, request);
  }

  /**
   * "Mis números" (self-service, Ola 2): tasa de aceptación, rating y
   * completados por semana (últimas 4 semanas). Ver
   * {@link com.fixy.backend.service.ProviderSelfService#statsFor}.
   */
  @GetMapping("/stats")
  public ProviderStatsResponse stats(
      @PathVariable Long providerId,
      @RequestParam("token") String token
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    return selfService.statsFor(provider);
  }

  @GetMapping("/commissions")
  public ProviderCommissionSummary commissions(
      @PathVariable Long providerId,
      @RequestParam("token") String token
  ) {
    selfService.authenticate(providerId, token);
    return leadPaymentQueryService.summaryFor(providerId);
  }

  /**
   * "Voy en camino": caso real Nueva Era (lead #105) escribió "en 40 min
   * maso llega" a mano en el chat — esto lo convierte en un botón de un
   * toque, como Uber. Body opcional con ETA en minutos.
   */
  @PostMapping("/leads/{leadId}/on-my-way")
  public ProviderAssignedLeadSummary onMyWay(
      @PathVariable Long providerId,
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @RequestBody(required = false) OnMyWayRequest request
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    Integer etaMinutes = request == null ? null : request.etaMinutes();
    Lead updated = selfService.notifyOnTheWay(provider, leadId, etaMinutes);
    return ProviderAssignedLeadSummary.fromEntity(updated);
  }

  /**
   * Vincula la cuenta de Google al proveedor (Google Sign-In del panel): el
   * token del link mágico prueba posesión; después puede entrar desde
   * cualquier teléfono vía POST /api/public/auth/google-provider.
   */
  @PostMapping("/link-google")
  public LinkGoogleResponse linkGoogle(
      @PathVariable Long providerId,
      @RequestParam("token") String token,
      @Valid @RequestBody LinkGoogleRequest request
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    Provider linked = providerGoogleAuthService.link(provider, request.credential());
    return new LinkGoogleResponse(linked.getGoogleEmail());
  }

  public record LinkGoogleRequest(@NotNull String credential) {
  }

  public record LinkGoogleResponse(String googleEmail) {
  }

  /**
   * "Horario acordado con un toque": el proveedor propone día/franja desde
   * chips de su panel (ej. "mañana de 14 a 16"). El cliente lo confirma o
   * rechaza con un toque vía POST /api/public/leads/{id}/schedule-response.
   */
  @PostMapping("/leads/{leadId}/schedule-proposal")
  public ProviderAssignedLeadSummary scheduleProposal(
      @PathVariable Long providerId,
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @Valid @RequestBody ScheduleProposalRequest request
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    Lead updated = selfService.proposeSchedule(provider, leadId, request.proposal());
    return ProviderAssignedLeadSummary.fromEntity(updated);
  }

  @PostMapping("/leads/{leadId}/status")
  public ProviderAssignedLeadSummary updateStatus(
      @PathVariable Long providerId,
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @Valid @RequestBody StatusUpdateRequest request
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    Lead updated = selfService.updateLeadStatus(provider, leadId, request.status(), request.amountCharged(),
        request.cancelReason(), request.cancelReasonDetail(), request.arrivalWindow());
    return ProviderAssignedLeadSummary.fromEntity(updated, leadRatingRepository.findByLeadId(updated.getId()).orElse(null));
  }

  /**
   * Tier 1 (contrato §B.1): "cambió el precio" — protocolo "al llegar".
   * Exige >=1 foto subida por el proveedor (400 con el texto del contrato
   * si falta) y solo en ASSIGNED/IN_PROGRESS.
   */
  @PostMapping("/leads/{leadId}/price-change")
  public ProviderAssignedLeadSummary proposePriceChange(
      @PathVariable Long providerId,
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @Valid @RequestBody PriceChangeProposeRequest request
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    Lead updated = priceChangeService.propose(provider, leadId, request.amount(), request.reason());
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
    LeadMessageResponse posted = messageService.postFromOps(leadId, "provider", request.text());
    // Caso lead #200: si el cliente sigue sin WhatsApp, este es EL momento
    // de insistir una única vez — hay una respuesta real esperándolo.
    leadAgentService.afterProviderMessage(leadId);
    return posted;
  }

  /**
   * @param cancelReason       OBLIGATORIO (400 si falta/vacío) cuando
   *                            {@code status == CANCELLED} — el resto de los
   *                            status lo ignoran. Contrato con el frontend:
   *                            "sin_disponibilidad" | "zona" | "precio" | "otro".
   * @param cancelReasonDetail campo libre opcional, máx 300 caracteres.
   * @param arrivalWindow      Tier 2 (contrato §B.2): franja corta opcional,
   *                            solo aplica cuando {@code status == ASSIGNED}.
   */
  public record StatusUpdateRequest(
      @NotNull LeadStatus status,
      BigDecimal amountCharged,
      String cancelReason,
      String cancelReasonDetail,
      String arrivalWindow
  ) {
  }

  public record ScheduleProposalRequest(@NotNull String proposal) {
  }

  /** Tier 2 (contrato §A.4): ambos opcionales, al menos uno presente
   * (validado en el controller, no acá — un record no puede expresar
   * "al menos uno de dos" con Bean Validation simple). */
  public record AvailabilityUpdateRequest(Boolean acceptingWork, String availabilityWindows) {
  }

  public record OnMyWayRequest(Integer etaMinutes) {
  }

  /** Tier 2 (contrato §B.2): franja corta opcional que manda el proveedor al aceptar. */
  public record AcceptOpportunityRequest(String arrivalWindow) {
  }
}
