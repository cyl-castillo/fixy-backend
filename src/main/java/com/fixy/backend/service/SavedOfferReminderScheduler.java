package com.fixy.backend.service;

import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.model.PushSubscription;
import com.fixy.backend.repository.OfferRepository;
import com.fixy.backend.repository.PushSubscriptionRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Fase Push-2 (roadmap "Fixy referencia de ofertas" §4, enganche): recordatorio
 * "«oferta» vence mañana" para quien guardó una oferta ({@code
 * PushSubscription.savedOfferIds}, V22) y su vigencia entra en las próximas
 * 24h. Mismo patrón que los demás schedulers del repo (ej.
 * {@link OfferExpirationScheduler}): corre cada hora, apagable por property,
 * {@code processOnce()} invocable desde tests.
 *
 * <p>Dos cosas pasan en la misma pasada por suscripción, independientes entre
 * sí:
 * <ol>
 *   <li><b>Higiene del CSV</b>: los ids que ya no existen o pasaron a
 *   {@code EXPIRED}/{@code REJECTED} se sacan de {@code savedOfferIds} —
 *   sin esto la lista crece para siempre con ofertas muertas que el
 *   scheduler igual tiene que releer cada hora.</li>
 *   <li><b>El aviso</b>: de las ofertas que sobreviven la limpieza, la que
 *   esté {@code ACTIVE} con {@code validUntil} más próxima dentro de las
 *   próximas 24h (si hay varias, la más próxima) dispara UN push — throttle
 *   de {@value #THROTTLE_DAYS} día por suscripción ({@code
 *   lastSavedReminderAt}), igual que {@code OfferDigestService} pero con
 *   ventana propia y más corta (es un recordatorio puntual de vencimiento,
 *   no un digest semanal).</li>
 * </ol>
 *
 * <p>El envío usa {@link PushNotificationService#notifySubscription} (no
 * {@code notifyLeadHasNews}): la suscripción puede ser de un visitante sin
 * {@code leadId} — guardar una oferta no requiere haber hecho un pedido.
 */
@Service
public class SavedOfferReminderScheduler {

  private static final Logger log = LoggerFactory.getLogger(SavedOfferReminderScheduler.class);

  /** Ofertas guardadas que se sacan del CSV apenas dejan de estar públicamente vigentes o vivas. */
  private static final Set<OfferStatus> STALE_STATUSES = EnumSet.of(OfferStatus.EXPIRED, OfferStatus.REJECTED);
  static final int THROTTLE_DAYS = 1;
  private static final String REMINDER_URL_TEMPLATE = "/oferta/%d?r=1";
  private static final String DEFAULT_BODY = "Última oportunidad, vence mañana.";

  private final PushSubscriptionRepository pushSubscriptionRepository;
  private final OfferRepository offerRepository;
  private final PushNotificationService pushNotificationService;
  private final boolean enabled;
  private final Clock clock;

  public SavedOfferReminderScheduler(
      PushSubscriptionRepository pushSubscriptionRepository,
      OfferRepository offerRepository,
      PushNotificationService pushNotificationService,
      @Value("${fixy.offers.saved-reminder.enabled:true}") boolean enabled,
      Clock clock
  ) {
    this.pushSubscriptionRepository = pushSubscriptionRepository;
    this.offerRepository = offerRepository;
    this.pushNotificationService = pushNotificationService;
    this.enabled = enabled;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.offers.saved-reminder.scheduler-fixed-delay-ms:3600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int sent = processOnce();
    if (sent > 0) {
      log.info("recordatorio de guardadas por vencer: {} aviso(s) enviados", sent);
    }
  }

  /** Un ciclo del job, invocable directamente desde tests. Devuelve cuántos recordatorios se enviaron. */
  public int processOnce() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime in24h = now.plusHours(24);
    OffsetDateTime throttleCutoff = now.minusDays(THROTTLE_DAYS);

    List<PushSubscription> candidates = pushSubscriptionRepository.findBySavedOfferIdsIsNotNull();
    int sent = 0;

    for (PushSubscription sub : candidates) {
      List<Long> ids = SavedOfferIdsCodec.parse(sub.getSavedOfferIds());
      if (ids.isEmpty()) {
        continue;
      }

      Map<Long, Offer> byId = offerRepository.findAllById(ids).stream()
          .collect(Collectors.toMap(Offer::getId, Function.identity()));

      List<Long> kept = new ArrayList<>();
      Offer soonestToExpire = null;
      for (Long id : ids) {
        Offer offer = byId.get(id);
        if (offer == null || STALE_STATUSES.contains(offer.getStatus())) {
          continue; // ya no existe, o vencida/rechazada: se limpia del CSV.
        }
        kept.add(id);
        if (isDueWithin24h(offer, now, in24h)
            && (soonestToExpire == null || offer.getValidUntil().isBefore(soonestToExpire.getValidUntil()))) {
          soonestToExpire = offer;
        }
      }

      boolean changed = kept.size() != ids.size();
      if (changed) {
        sub.setSavedOfferIds(SavedOfferIdsCodec.format(kept));
      }

      boolean eligibleForReminder = sub.getLastSavedReminderAt() == null
          || sub.getLastSavedReminderAt().isBefore(throttleCutoff);
      boolean shouldRemind = soonestToExpire != null && eligibleForReminder;
      if (shouldRemind) {
        sub.setLastSavedReminderAt(now);
        changed = true;
      }

      if (changed) {
        pushSubscriptionRepository.save(sub);
      }

      if (shouldRemind) {
        sendReminder(sub, soonestToExpire);
        sent++;
      }
    }

    return sent;
  }

  private boolean isDueWithin24h(Offer offer, OffsetDateTime now, OffsetDateTime in24h) {
    if (offer.getStatus() != OfferStatus.ACTIVE || offer.getValidUntil() == null) {
      return false;
    }
    return !offer.getValidUntil().isBefore(now) && offer.getValidUntil().isBefore(in24h);
  }

  private void sendReminder(PushSubscription sub, Offer offer) {
    String title = "«%s» vence mañana".formatted(offer.getTitle());
    String discount = offer.getDiscountText();
    String body = (discount == null || discount.isBlank()) ? DEFAULT_BODY : discount;
    pushNotificationService.notifySubscription(sub, title, body, REMINDER_URL_TEMPLATE.formatted(offer.getId()));
  }
}
