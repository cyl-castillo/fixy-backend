package com.fixy.backend.service;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.model.PushSubscription;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import com.fixy.backend.repository.PushSubscriptionRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Fase 5 (panel self-service del comercio, roadmap "Fixy referencia de
 * ofertas" §5): avisa al DUEÑO cuando una oferta suya está por vencer, para
 * que la renueve con un toque desde su panel sin depender de que ops se
 * acuerde. Mismo patrón que los demás schedulers del repo (ej.
 * {@link SavedOfferReminderScheduler}): corre cada hora, apagable por
 * property, {@code processOnce()} invocable desde tests.
 *
 * <p>Universo: ofertas {@code ACTIVE} con {@code validUntil} dentro de las
 * próximas {@value #WINDOW_HOURS}h, agrupadas por comercio. Por comercio, se
 * elige la oferta más próxima a vencer (una sola, aunque tenga varias en la
 * ventana — mismo criterio que {@code SavedOfferReminderScheduler}):
 * <ul>
 *   <li>si el comercio tiene alguna {@link PushSubscription} propia
 *   ({@code businessId}, seteado por {@code merchantToken} en el alta
 *   pública de push): le manda el aviso a CADA una de sus suscripciones
 *   (puede tener más de un dispositivo), con throttle de 1 día por
 *   suscripción ({@code lastMerchantReminderAt}) — no hay columna nueva en
 *   {@code Offer} para "ya avisado por esta oferta", el throttle por
 *   suscripción + "elegir siempre la más próxima" alcanza en la práctica
 *   (una vez avisado, la ventana de 48h no vuelve a producir una oferta más
 *   próxima que dispare antes de que pase el throttle);</li>
 *   <li>si el comercio NO tiene ninguna suscripción: la oferta se suma al
 *   digest best-effort a ops por Telegram de esa corrida ({@code
 *   TelegramNotifyService#notifyMerchantOffersExpiringWithoutOwnerPush}) —
 *   sin eso, esas ofertas se vencían en silencio total.</li>
 * </ul>
 */
@Service
public class MerchantOfferExpiryScheduler {

  private static final Logger log = LoggerFactory.getLogger(MerchantOfferExpiryScheduler.class);

  static final int WINDOW_HOURS = 48;
  static final int THROTTLE_DAYS = 1;
  private static final String DEFAULT_BODY = "Extendela o pausala cuando quieras desde tu panel.";

  private final OfferRepository offerRepository;
  private final BusinessRepository businessRepository;
  private final PushSubscriptionRepository pushSubscriptionRepository;
  private final PushNotificationService pushNotificationService;
  private final TelegramNotifyService telegramNotifyService;
  private final boolean enabled;
  private final Clock clock;

  public MerchantOfferExpiryScheduler(
      OfferRepository offerRepository,
      BusinessRepository businessRepository,
      PushSubscriptionRepository pushSubscriptionRepository,
      PushNotificationService pushNotificationService,
      TelegramNotifyService telegramNotifyService,
      @Value("${fixy.offers.merchant-reminder.enabled:true}") boolean enabled,
      Clock clock
  ) {
    this.offerRepository = offerRepository;
    this.businessRepository = businessRepository;
    this.pushSubscriptionRepository = pushSubscriptionRepository;
    this.pushNotificationService = pushNotificationService;
    this.telegramNotifyService = telegramNotifyService;
    this.enabled = enabled;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.offers.merchant-reminder.scheduler-fixed-delay-ms:3600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int sent = processOnce();
    if (sent > 0) {
      log.info("aviso de vencimiento al dueño: {} push(es) enviados", sent);
    }
  }

  /** Un ciclo del job, invocable directamente desde tests. Devuelve cuántos push se enviaron (el digest a ops no cuenta acá). */
  public int processOnce() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime windowEnd = now.plusHours(WINDOW_HOURS);
    OffsetDateTime throttleCutoff = now.minusDays(THROTTLE_DAYS);

    List<Offer> candidates = offerRepository.findByStatusAndValidUntilBefore(OfferStatus.ACTIVE, windowEnd).stream()
        .filter(offer -> offer.getValidUntil() != null && !offer.getValidUntil().isBefore(now))
        .toList();
    if (candidates.isEmpty()) {
      return 0;
    }

    Map<Long, List<Offer>> byBusiness = candidates.stream()
        .collect(Collectors.groupingBy(Offer::getBusinessId, LinkedHashMap::new, Collectors.toList()));

    int sent = 0;
    List<TelegramNotifyService.ExpiringWithoutOwnerPush> noPush = new ArrayList<>();

    for (Map.Entry<Long, List<Offer>> entry : byBusiness.entrySet()) {
      Long businessId = entry.getKey();
      Offer soonest = entry.getValue().stream()
          .min(Comparator.comparing(Offer::getValidUntil))
          .orElse(null);
      if (soonest == null) {
        continue;
      }

      List<PushSubscription> subs = pushSubscriptionRepository.findByBusinessId(businessId);
      if (subs.isEmpty()) {
        businessRepository.findById(businessId)
            .ifPresent(business -> noPush.add(new TelegramNotifyService.ExpiringWithoutOwnerPush(business, soonest)));
        continue;
      }

      Business business = businessRepository.findById(businessId).orElse(null);
      if (business == null || business.getPanelToken() == null || business.getPanelToken().isBlank()) {
        // No debería pasar (la suscripción solo se liga vía un panelToken que ya existe), pero sin
        // token no hay URL de panel a la que apuntar el push — se salta, no rompe la corrida.
        continue;
      }

      for (PushSubscription sub : subs) {
        boolean eligible = sub.getLastMerchantReminderAt() == null
            || sub.getLastMerchantReminderAt().isBefore(throttleCutoff);
        if (!eligible) {
          continue;
        }
        sub.setLastMerchantReminderAt(now);
        pushSubscriptionRepository.save(sub);
        sendReminder(sub, business, soonest);
        sent++;
      }
    }

    if (!noPush.isEmpty()) {
      try {
        telegramNotifyService.notifyMerchantOffersExpiringWithoutOwnerPush(noPush);
      } catch (Exception ex) {
        log.warn("aviso a ops de ofertas por vencer sin push falló: {}", ex.getMessage());
      }
    }

    return sent;
  }

  private void sendReminder(PushSubscription sub, Business business, Offer offer) {
    String title = "«%s» vence en 2 días — renovala con un toque".formatted(offer.getTitle());
    String discount = offer.getDiscountText();
    String body = (discount == null || discount.isBlank()) ? DEFAULT_BODY : discount;
    String url = "/mi-comercio/%s".formatted(business.getPanelToken());
    pushNotificationService.notifySubscription(sub, title, body, url);
  }
}
