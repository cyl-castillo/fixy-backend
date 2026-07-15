package com.fixy.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.model.PushSubscription;
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

  static {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  private final PushSubscriptionRepository repository;
  private final ObjectMapper objectMapper;
  private final String publicKey;
  private final boolean enabled;
  private PushService pushService;

  public PushNotificationService(
      PushSubscriptionRepository repository,
      ObjectMapper objectMapper,
      @Value("${fixy.push.vapid-public-key:}") String publicKey,
      @Value("${fixy.push.vapid-private-key:}") String privateKey,
      @Value("${fixy.push.subject:mailto:soporte@fixy.com.uy}") String subject
  ) {
    this.repository = repository;
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
    repository.save(subscription);
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
   * Avisa al cliente que tiene novedades en el chat de su lead. No-op
   * silencioso si no hay claves VAPID o el cliente no se suscribió nunca.
   * Async: nunca debe demorar el flujo de mensajería.
   */
  @Async
  public void notifyLeadHasNews(Long leadId, String title, String body) {
    if (!isEnabled() || leadId == null) return;
    List<PushSubscription> subs = repository.findByLeadId(leadId);
    if (subs.isEmpty()) return;
    sendToAll(subs, title, body, "/");
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

  private void sendToAll(List<PushSubscription> subs, String title, String body, String url) {
    for (PushSubscription sub : subs) {
      try {
        send(sub, title, body, url);
      } catch (Exception ex) {
        log.warn("push send failed sub={}: {}", sub.getId(), ex.getMessage());
      }
    }
  }

  private void send(PushSubscription sub, String title, String body, String url) throws Exception {
    Subscription.Keys keys = new Subscription.Keys(sub.getP256dh(), sub.getAuth());
    Subscription subscription = new Subscription(sub.getEndpoint(), keys);
    String payload = objectMapper.writeValueAsString(Map.of(
        "title", title,
        "body", body,
        "url", url
    ));
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
