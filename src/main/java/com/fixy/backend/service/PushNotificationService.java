package com.fixy.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.model.CoverageZone;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.PushSubscription;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.PushSubscriptionRepository;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.List;
import java.util.Map;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Notificaciones push del navegador (Web Push / VAPID), Ola UX: el agente
 * dice "te aviso por acá" y hoy eso solo es real si el cliente tiene la
 * pestaña abierta. Esto la hace real incluso con la pestaña cerrada.
 *
 * Mismo patrón que {@link WhatsAppService} / {@link TelegramNotifyService}:
 * constructor con {@code @Value(...:)}, "enabled" solo si hay credenciales
 * (claves VAPID), sin claves = no-op silencioso, catch-all que loguea y
 * nunca rompe el flujo de negocio. Envío async.
 *
 * Config requerida:
 *   fixy.push.vapid-public-key   (base64url, comienza con "B")
 *   fixy.push.vapid-private-key  (base64url)
 *   fixy.push.subject            (mailto: o https:, requerido por la spec VAPID)
 *
 * Suscripciones muertas: si el push service devuelve 404/410 (Gone), la
 * suscripción ya no sirve (el usuario desinstaló, limpió datos, etc.) y se
 * borra sola — evita reintentos eternos contra un endpoint muerto.
 */
@Service
public class PushNotificationService {

  private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

  /** Deep-link default de todos los triggers existentes (matching, mensajes, comisiones...): abre la app en la raíz. */
  private static final String DEFAULT_URL = "/";

  static {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  private final PushSubscriptionRepository repository;
  private final LeadRepository leadRepository;
  private final PublicLeadAbuseProtectionService abuseProtectionService;
  private final ObjectMapper objectMapper;
  private final String publicKey;
  private final boolean enabled;
  private PushService pushService;

  public PushNotificationService(
      PushSubscriptionRepository repository,
      LeadRepository leadRepository,
      PublicLeadAbuseProtectionService abuseProtectionService,
      ObjectMapper objectMapper,
      @Value("${fixy.push.vapid-public-key:}") String publicKey,
      @Value("${fixy.push.vapid-private-key:}") String privateKey,
      @Value("${fixy.push.subject:mailto:soporte@fixy.com.uy}") String subject
  ) {
    this.repository = repository;
    this.leadRepository = leadRepository;
    this.abuseProtectionService = abuseProtectionService;
    this.objectMapper = objectMapper;
    this.publicKey = publicKey;
    this.enabled = publicKey != null && !publicKey.isBlank()
        && privateKey != null && !privateKey.isBlank();
    if (enabled) {
      try {
        this.pushService = new PushService(publicKey, privateKey, subject);
      } catch (GeneralSecurityException ex) {
        log.warn("PushNotificationService: claves VAPID inválidas, quedando deshabilitado: {}", ex.getMessage());
        this.pushService = null;
      }
    }
    log.info("PushNotificationService initialized: enabled={}", enabled && pushService != null);
  }

  public boolean isEnabled() {
    return enabled && pushService != null;
  }

  /** Clave pública VAPID que el frontend necesita para {@code PushManager.subscribe()}. */
  public String vapidPublicKey() {
    return publicKey;
  }

  public void saveSubscriptionForLead(Long leadId, String endpoint, String p256dh, String auth) {
    if (repository.existsByLeadIdAndEndpoint(leadId, endpoint)) return;
    PushSubscription subscription = new PushSubscription();
    subscription.setLeadId(leadId);
    subscription.setEndpoint(endpoint);
    subscription.setP256dh(p256dh);
    subscription.setAuth(auth);
    subscription.setZone(resolveLeadZone(leadId));
    repository.save(subscription);
  }

  /**
   * Zona canónica del lead al momento del alta (Fase Push-1): {@code
   * Lead.location} resuelto contra {@link CoverageZone#fromLabel}, mismo
   * alias/jerarquía que usa el matching. Null si el lead no existe o su
   * zona declarada no es una que Fixy reconozca — una suscripción sin zona
   * simplemente queda fuera del digest, no rompe el alta.
   */
  private String resolveLeadZone(Long leadId) {
    if (leadId == null) return null;
    Lead lead = leadRepository.findById(leadId).orElse(null);
    if (lead == null) return null;
    return CoverageZone.fromLabel(lead.getLocation()).map(CoverageZone::label).orElse(null);
  }

  /**
   * Backfill de zona (Fase Push-1) para suscripciones de cliente que
   * quedaron sin ella — las dadas de alta antes de esta fase, o cuyo lead
   * no tenía zona reconocida en su momento pero sí ahora. Idempotente y
   * barato (dataset chico): se corre en cada boot desde
   * {@code PushSubscriptionZoneBackfillConfig}, no solo una vez, porque no
   * cuesta nada y cierra el caso de un lead que corrige su zona después
   * del alta de la suscripción.
   */
  public int backfillMissingZones() {
    List<PushSubscription> candidates = repository.findByLeadIdIsNotNullAndZoneIsNull();
    int updated = 0;
    for (PushSubscription sub : candidates) {
      String zone = resolveLeadZone(sub.getLeadId());
      if (zone == null) continue;
      sub.setZone(zone);
      repository.save(sub);
      updated++;
    }
    return updated;
  }

  public void saveSubscriptionForProvider(Long providerId, String endpoint, String p256dh, String auth) {
    if (repository.existsByProviderIdAndEndpoint(providerId, endpoint)) return;
    PushSubscription subscription = new PushSubscription();
    subscription.setProviderId(providerId);
    subscription.setEndpoint(endpoint);
    subscription.setP256dh(p256dh);
    subscription.setAuth(auth);
    repository.save(subscription);
  }

  /**
   * Alta pública de suscripción (Fase Push-2, enganche) — {@code POST
   * /api/public/push-subscriptions}, sin token: la llama la PWA directo,
   * antes de que exista un lead o un provider (visitante), o un dispositivo
   * que ya tenía fila. UPSERT por {@code endpoint} (mismo dispositivo =
   * misma fila): si ya existe, se actualizan zone/savedOfferIds/claves pero
   * NUNCA {@code leadId}/{@code providerId} — una fila que ya pertenece a un
   * lead o proveedor sigue perteneciéndole aunque la PWA vuelva a llamar
   * este endpoint sin saberlo (por ejemplo, el visitante que después completa
   * un pedido reusa el mismo endpoint del navegador).
   *
   * <p>{@code zone} se valida contra {@link CoverageZone}: si no es una que
   * Fixy reconoce, queda en null — igual que {@link #resolveLeadZone}.
   * Validación de abuso ({@code savedOfferIds} y rate limit por IP) antes de
   * tocar la base, mismo orden que el resto de las rutas públicas.
   */
  public void upsertPublicSubscription(
      String clientIp, String endpoint, String p256dh, String auth, String zoneRaw, List<Long> savedOfferIds
  ) {
    abuseProtectionService.validatePushSubscription(clientIp, savedOfferIds);
    String zone = CoverageZone.fromLabel(zoneRaw).map(CoverageZone::label).orElse(null);
    PushSubscription subscription = repository.findByEndpoint(endpoint).orElseGet(PushSubscription::new);
    subscription.setEndpoint(endpoint);
    subscription.setP256dh(p256dh);
    subscription.setAuth(auth);
    subscription.setZone(zone);
    subscription.setSavedOfferIds(SavedOfferIdsCodec.format(savedOfferIds));
    repository.save(subscription);
  }

  /**
   * Avisa al cliente que tiene novedades en el chat de su lead. No-op
   * silencioso si no hay claves VAPID o el cliente no se suscribió nunca.
   * Async: nunca debe demorar el flujo de mensajería.
   */
  @Async
  public void notifyLeadHasNews(Long leadId, String title, String body) {
    doNotifyLeadHasNews(leadId, title, body, DEFAULT_URL);
  }

  /**
   * Igual que {@link #notifyLeadHasNews(Long, String, String)} con deep-link
   * parametrizable (Fase Push-1: el digest de ofertas abre {@code
   * /ofertas}, no la raíz). Overload en vez de agregar un parámetro al
   * método existente para no tocar los 14 call sites de los triggers
   * operativos ya en prod.
   */
  @Async
  public void notifyLeadHasNews(Long leadId, String title, String body, String url) {
    doNotifyLeadHasNews(leadId, title, body, url);
  }

  private void doNotifyLeadHasNews(Long leadId, String title, String body, String url) {
    if (!isEnabled() || leadId == null) return;
    List<PushSubscription> subs = repository.findByLeadId(leadId);
    if (subs.isEmpty()) return;
    sendToAll(subs, title, body, url);
  }

  /**
   * Avisa al proveedor que el cliente le escribió en un lead asignado, o que
   * tiene una oportunidad nueva. No-op silencioso si no hay claves VAPID o
   * el proveedor no se suscribió nunca. Async por la misma razón.
   */
  @Async
  public void notifyProvider(Long providerId, String accessToken, String title, String body) {
    if (!isEnabled() || providerId == null) return;
    List<PushSubscription> subs = repository.findByProviderId(providerId);
    if (subs.isEmpty()) return;
    String url = accessToken == null || accessToken.isBlank()
        ? "/"
        : "/p/" + providerId + "/" + accessToken;
    sendToAll(subs, title, body, url);
  }

  /**
   * Envía a UNA suscripción puntual, sin pasar por lead/provider (Fase
   * Push-2, {@code SavedOfferReminderScheduler}: la suscripción puede ser de
   * un visitante sin {@code leadId}). Mismo criterio que el resto: no-op si
   * no hay claves VAPID, catch-all silencioso, borra la suscripción muerta
   * en 404/410 ({@link #send}). Síncrono a propósito — el caller es un
   * scheduler que ya corre fuera del hilo de un request, no hace falta
   * desacoplar con {@code @Async}, y el caller necesita saber que el envío
   * ya se intentó antes de persistir el throttle.
   */
  public void notifySubscription(PushSubscription sub, String title, String body, String url) {
    if (!isEnabled() || sub == null || sub.getId() == null) return;
    try {
      send(sub, title, body, url);
    } catch (Exception ex) {
      log.warn("push send failed sub={}: {}", sub.getId(), ex.getMessage());
    }
  }

  private void sendToAll(List<PushSubscription> subs, String title, String body, String url) {
    for (PushSubscription sub : subs) {
      try {
        send(sub, title, body, url);
      } catch (Exception ex) {
        log.warn("push send failed sub={}: {}", sub.getId(), ex.getMessage());
      }
    }
  }

  /** Extraído para test unitario directo del contrato del payload (deep-link incluido) sin pasar por cifrado real. */
  static Map<String, String> payloadMap(String title, String body, String url) {
    return Map.of("title", title, "body", body, "url", url);
  }

  private void send(PushSubscription sub, String title, String body, String url) throws Exception {
    Subscription.Keys keys = new Subscription.Keys(sub.getP256dh(), sub.getAuth());
    Subscription subscription = new Subscription(sub.getEndpoint(), keys);
    String payload = objectMapper.writeValueAsString(payloadMap(title, body, url));
    Notification notification = new Notification(subscription, payload);
    org.apache.http.HttpResponse response = pushService.send(notification);
    int status = response.getStatusLine().getStatusCode();
    if (status == 404 || status == 410) {
      log.info("push subscription id={} gone (status={}), borrando", sub.getId(), status);
      repository.deleteById(sub.getId());
      return;
    }
    if (status >= 200 && status < 300) {
      log.info("push sent sub={} status={}", sub.getId(), status);
    } else {
      log.warn("push send unexpected status sub={} status={}", sub.getId(), status);
    }
  }
}
