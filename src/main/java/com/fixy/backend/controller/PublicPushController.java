package com.fixy.backend.controller;

import com.fixy.backend.dto.PublicPushSubscriptionRequest;
import com.fixy.backend.dto.PushSubscriptionRequest;
import com.fixy.backend.service.LeadService;
import com.fixy.backend.service.ProviderSelfService;
import com.fixy.backend.service.PushNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints públicos de Web Push (Ola UX): clave VAPID pública para que el
 * navegador pueda suscribirse, alta de suscripción para cliente/proveedor
 * (mismo auth por token que el resto de {@code /api/public} — sin token no
 * hay mutación, IDs secuenciales y adivinables) y {@link #upsertSubscription}
 * (Fase Push-2, enganche), la ruta SIN token para el visitante o para un
 * upsert por endpoint (ver javadoc de {@code PushNotificationService#upsertPublicSubscription}).
 */
@RestController
@RequestMapping("/api/public")
public class PublicPushController {

  private final PushNotificationService pushService;
  private final LeadService leadService;
  private final ProviderSelfService providerSelfService;

  public PublicPushController(
      PushNotificationService pushService,
      LeadService leadService,
      ProviderSelfService providerSelfService
  ) {
    this.pushService = pushService;
    this.leadService = leadService;
    this.providerSelfService = providerSelfService;
  }

  @GetMapping("/push/vapid-public-key")
  public Map<String, String> vapidPublicKey() {
    return Map.of("publicKey", pushService.isEnabled() ? pushService.vapidPublicKey() : "");
  }

  @PostMapping("/leads/{leadId}/push-subscription")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void subscribeLead(
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @Valid @RequestBody PushSubscriptionRequest request
  ) {
    leadService.requirePublicToken(leadId, token);
    pushService.saveSubscriptionForLead(leadId, request.endpoint(), request.keys().p256dh(), request.keys().auth());
  }

  @PostMapping("/providers/{providerId}/push-subscription")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void subscribeProvider(
      @PathVariable Long providerId,
      @RequestParam("token") String token,
      @Valid @RequestBody PushSubscriptionRequest request
  ) {
    providerSelfService.authenticate(providerId, token);
    pushService.saveSubscriptionForProvider(providerId, request.endpoint(), request.keys().p256dh(), request.keys().auth());
  }

  /**
   * Alta pública de suscripción (Fase Push-2, enganche): sin token, para el
   * visitante que todavía no tiene lead ni provider, o para actualizar
   * zone/savedOfferIds/claves de un endpoint ya conocido (upsert, ver
   * {@code PushNotificationService#upsertPublicSubscription}). Responde
   * siempre {@code {"ok": true}} — mismo contrato que el resto de las rutas
   * públicas fire-and-forget de la PWA (ej. {@code OfferInquiryService}).
   */
  @PostMapping("/push-subscriptions")
  public Map<String, Object> upsertSubscription(
      @Valid @RequestBody PublicPushSubscriptionRequest request,
      HttpServletRequest httpRequest
  ) {
    pushService.upsertPublicSubscription(
        httpRequest.getRemoteAddr(),
        request.endpoint(),
        request.keys().p256dh(),
        request.keys().auth(),
        request.zone(),
        request.savedOfferIds());
    return Map.of("ok", true);
  }
}
