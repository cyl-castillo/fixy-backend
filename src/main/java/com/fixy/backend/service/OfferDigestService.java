package com.fixy.backend.service;

import com.fixy.backend.dto.OfferDigestPreviewResponse;
import com.fixy.backend.dto.OfferDigestSendResponse;
import com.fixy.backend.dto.OfferDigestZonePreview;
import com.fixy.backend.dto.OfferPublicResponse;
import com.fixy.backend.model.CoverageZone;
import com.fixy.backend.model.PushSubscription;
import com.fixy.backend.repository.PushSubscriptionRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Digest semanal "las ofertas de tu zona" (Fase Push-1,
 * FIXY_OFERTAS_PUSH_Y_MAPA.md §3): primera versión disparada A MANO desde
 * ops — {@link #preview()} para ver a quién y con qué antes de tocar nada,
 * {@link #send()} para ejecutar. Sin scheduler automático todavía (Fase
 * Push-2, deliberadamente fuera de este commit).
 *
 * <p>Reglas anti-spam DURAS en código, no en criterio de quien aprieta el
 * botón: mínimo {@value #MIN_ACTIVE_OFFERS} ofertas vigentes en la zona del
 * suscriptor (propias + {@code allZones}, mismo criterio que
 * {@link OfferService#listPublic}) y máximo un envío cada
 * {@value #RECENCY_DAYS} días por suscripción ({@code
 * PushSubscription.lastOffersDigestAt}).
 *
 * <p>Solo suscripciones de CLIENTE ({@code leadId != null}) — las de
 * proveedor no participan de este loop, el digest es de adquisición/
 * retención de cliente (Loop 1 del roadmap), no un canal operativo.
 */
@Service
public class OfferDigestService {

  private static final Logger log = LoggerFactory.getLogger(OfferDigestService.class);

  static final int MIN_ACTIVE_OFFERS = 3;
  static final int RECENCY_DAYS = 7;
  private static final String NO_ZONE_LABEL = "sin zona";
  private static final int DIGEST_OFFER_TITLES = 3;
  private static final String DIGEST_URL = "/ofertas";

  private final PushSubscriptionRepository pushSubscriptionRepository;
  private final OfferService offerService;
  private final PushNotificationService pushNotificationService;
  private final Clock clock;

  public OfferDigestService(
      PushSubscriptionRepository pushSubscriptionRepository,
      OfferService offerService,
      PushNotificationService pushNotificationService,
      Clock clock
  ) {
    this.pushSubscriptionRepository = pushSubscriptionRepository;
    this.offerService = offerService;
    this.pushNotificationService = pushNotificationService;
    this.clock = clock;
  }

  /**
   * A quiénes y con qué se les enviaría el digest AHORA, sin mandar nada —
   * agrupado por zona (misma zona → mismo conteo de ofertas vigentes y
   * mismo criterio de {@code willSend}, la recencia es lo único que varía
   * por suscripción dentro de una zona).
   */
  public OfferDigestPreviewResponse preview() {
    List<PushSubscription> clientSubs = pushSubscriptionRepository.findByLeadIdIsNotNull();
    OffsetDateTime recentCutoff = OffsetDateTime.now(clock).minusDays(RECENCY_DAYS);

    List<OfferDigestZonePreview> zones = new ArrayList<>();
    int totalToSend = 0;

    for (CoverageZone zone : CoverageZone.values()) {
      List<PushSubscription> subsInZone = clientSubs.stream()
          .filter(sub -> zone.label().equals(sub.getZone()))
          .toList();
      if (subsInZone.isEmpty()) {
        continue;
      }
      int activeOffers = offerService.listPublic(zone.label(), null).size();
      int eligible = (int) subsInZone.stream().filter(sub -> isEligibleForDigest(sub, recentCutoff)).count();
      boolean willSend = activeOffers >= MIN_ACTIVE_OFFERS && eligible > 0;
      zones.add(new OfferDigestZonePreview(
          zone.label(), subsInZone.size(), activeOffers, willSend, digestReason(activeOffers, willSend)));
      if (willSend) {
        totalToSend += eligible;
      }
    }

    List<PushSubscription> withoutZone = clientSubs.stream().filter(sub -> sub.getZone() == null).toList();
    if (!withoutZone.isEmpty()) {
      zones.add(new OfferDigestZonePreview(NO_ZONE_LABEL, withoutZone.size(), 0, false,
          "sin zona asociada a la suscripción, no se le puede armar el digest"));
    }

    return new OfferDigestPreviewResponse(zones, clientSubs.size(), totalToSend);
  }

  private String digestReason(int activeOffers, boolean willSend) {
    if (willSend) {
      return "ok";
    }
    if (activeOffers < MIN_ACTIVE_OFFERS) {
      return "menos de %d ofertas vigentes en la zona (tiene %d)".formatted(MIN_ACTIVE_OFFERS, activeOffers);
    }
    return "todas las suscripciones de la zona recibieron un digest en los últimos %d días".formatted(RECENCY_DAYS);
  }

  private boolean isEligibleForDigest(PushSubscription sub, OffsetDateTime recentCutoff) {
    return sub.getLastOffersDigestAt() == null || sub.getLastOffersDigestAt().isBefore(recentCutoff);
  }

  /**
   * Ejecuta el digest: una pasada por cada suscripción de cliente, mismas
   * reglas que {@link #preview()}. El envío en sí va por
   * {@link PushNotificationService#notifyLeadHasNews(Long, String, String, String)}
   * (async, fire-and-forget — mismo criterio que el resto de los triggers
   * operativos), así que los contadores reflejan la evaluación de las
   * reglas de negocio, no la confirmación de entrega del push service del
   * navegador.
   */
  public OfferDigestSendResponse send() {
    List<PushSubscription> clientSubs = pushSubscriptionRepository.findByLeadIdIsNotNull();
    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime recentCutoff = now.minusDays(RECENCY_DAYS);

    int sent = 0;
    int skippedRecent = 0;
    int skippedFewOffers = 0;
    int skippedNoZone = 0;

    for (PushSubscription sub : clientSubs) {
      String zone = sub.getZone();
      if (zone == null) {
        skippedNoZone++;
        continue;
      }
      List<OfferPublicResponse> offers = offerService.listPublic(zone, null);
      if (offers.size() < MIN_ACTIVE_OFFERS) {
        skippedFewOffers++;
        continue;
      }
      if (!isEligibleForDigest(sub, recentCutoff)) {
        skippedRecent++;
        continue;
      }
      sendDigest(sub, zone, offers, now);
      sent++;
    }

    log.info("digest de ofertas: sent={} skippedRecent={} skippedFewOffers={} skippedNoZone={}",
        sent, skippedRecent, skippedFewOffers, skippedNoZone);
    return new OfferDigestSendResponse(sent, skippedRecent, skippedFewOffers, skippedNoZone);
  }

  private void sendDigest(PushSubscription sub, String zone, List<OfferPublicResponse> offers, OffsetDateTime now) {
    String title = "Ofertas de esta semana en %s".formatted(zone);
    String body = offers.stream()
        .limit(DIGEST_OFFER_TITLES)
        .map(OfferPublicResponse::title)
        .collect(Collectors.joining(" · "));
    pushNotificationService.notifyLeadHasNews(sub.getLeadId(), title, body, DIGEST_URL);
    sub.setLastOffersDigestAt(now);
    pushSubscriptionRepository.save(sub);
  }
}
