package com.fixy.backend.service;

import com.fixy.backend.dto.OfferDigestSendResponse;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Fase Push-2 (roadmap "Fixy referencia de ofertas" §4, enganche): dispara
 * {@link OfferDigestService#send()} sin intervención de ops, dentro de una
 * ventana semanal configurada. Hasta esta fase el digest era 100% manual
 * ({@code POST /api/offers/digest/send} desde /admin).
 *
 * <p>Mismo patrón que los demás schedulers del repo (ej.
 * {@link OfferExpirationScheduler}): corre cada hora
 * ({@code scheduler-fixed-delay-ms}), apagable por property, {@code
 * processOnce()} invocable desde tests sin esperar al {@code @Scheduled}
 * real. La diferencia es que este NO actúa en cada corrida — solo cuando el
 * día/hora actual, en {@code America/Montevideo} (zona fija a propósito: la
 * cadencia semanal del negocio no depende de en qué huso corra el server),
 * coincide con la ventana configurada ({@code day-of-week}/{@code hour}).
 *
 * <p>Correr una vez por hora en vez de una vez por semana significa que,
 * dentro de la hora de la ventana, puede disparar más de una vez si el
 * scheduler tiene jitter o el proceso se reinicia — no hace falta throttle
 * propio acá: la regla de recencia por suscripción ({@code
 * OfferDigestService.RECENCY_DAYS = 7}) ya evita duplicados.
 */
@Service
public class OfferDigestAutoScheduler {

  private static final Logger log = LoggerFactory.getLogger(OfferDigestAutoScheduler.class);
  private static final ZoneId ZONE = ZoneId.of("America/Montevideo");

  private final OfferDigestService offerDigestService;
  private final TelegramNotifyService telegramNotifyService;
  private final boolean enabled;
  private final DayOfWeek dayOfWeek;
  private final int hour;
  private final Clock clock;

  public OfferDigestAutoScheduler(
      OfferDigestService offerDigestService,
      TelegramNotifyService telegramNotifyService,
      @Value("${fixy.offers.digest.auto.enabled:true}") boolean enabled,
      @Value("${fixy.offers.digest.auto.day-of-week:THURSDAY}") String dayOfWeek,
      @Value("${fixy.offers.digest.auto.hour:18}") int hour,
      Clock clock
  ) {
    this.offerDigestService = offerDigestService;
    this.telegramNotifyService = telegramNotifyService;
    this.enabled = enabled;
    this.dayOfWeek = DayOfWeek.valueOf(dayOfWeek.trim().toUpperCase(Locale.ROOT));
    this.hour = hour;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.offers.digest.auto.scheduler-fixed-delay-ms:3600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    OfferDigestSendResponse result = processOnce();
    if (result != null) {
      log.info("digest automático de ofertas: sent={} skippedRecent={} skippedFewOffers={} skippedNoZone={}",
          result.sent(), result.skippedRecent(), result.skippedFewOffers(), result.skippedNoZone());
    }
  }

  /**
   * Un ciclo del job, invocable directamente desde tests. {@code null} si no
   * es la ventana configurada (no se tocó nada); el resultado de
   * {@link OfferDigestService#send()} si sí corrió. Avisa a ops por Telegram
   * (best-effort) solo cuando efectivamente mandó algo.
   */
  public OfferDigestSendResponse processOnce() {
    ZonedDateTime now = OffsetDateTime.now(clock).atZoneSameInstant(ZONE);
    if (now.getDayOfWeek() != dayOfWeek || now.getHour() != hour) {
      return null;
    }
    OfferDigestSendResponse response = offerDigestService.send();
    if (response.sent() > 0) {
      try {
        telegramNotifyService.notifyAutoDigestSummary(response);
      } catch (Exception ex) {
        log.warn("aviso a ops del digest automático falló: {}", ex.getMessage());
      }
    }
    return response;
  }
}
