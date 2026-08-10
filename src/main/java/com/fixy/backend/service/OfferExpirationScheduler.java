package com.fixy.backend.service;

import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.OfferRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Historia 3.4: marca {@code active → expired} cuando pasa {@code validUntil}
 * — la ventaja estructural sobre Instagram del análisis original ("todo lo
 * visible está vivo"). Mismo patrón que los demás schedulers del repo (ej.
 * {@link LeadClosingScheduler}, {@link OrphanMatchRetryScheduler}):
 * apagable por property, {@code processOnce()} invocable desde tests sin
 * esperar al {@code @Scheduled} real.
 */
@Service
public class OfferExpirationScheduler {

  private static final Logger log = LoggerFactory.getLogger(OfferExpirationScheduler.class);

  private final OfferRepository offerRepository;
  private final boolean enabled;
  private final Clock clock;

  public OfferExpirationScheduler(
      OfferRepository offerRepository,
      @Value("${fixy.offers.expiration.enabled:true}") boolean enabled,
      Clock clock
  ) {
    this.offerRepository = offerRepository;
    this.enabled = enabled;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.offers.expiration.scheduler-fixed-delay-ms:3600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int expired = processOnce();
    if (expired > 0) {
      log.info("expiración de ofertas: {} oferta(s) pasaron a expired", expired);
    }
  }

  /** Un ciclo del job, invocable directamente desde tests. Devuelve cuántas ofertas expiraron. */
  public int processOnce() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    List<Offer> candidates = offerRepository.findByStatusAndValidUntilBefore(OfferStatus.ACTIVE, now);
    for (Offer offer : candidates) {
      offer.setStatus(OfferStatus.EXPIRED);
      offerRepository.save(offer);
    }
    return candidates.size();
  }
}
