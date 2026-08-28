package com.fixy.backend.service;

import com.fixy.backend.model.BusinessInquiry;
import com.fixy.backend.model.BusinessInquiryStatus;
import com.fixy.backend.repository.BusinessInquiryRepository;
import com.fixy.backend.repository.BusinessRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vencimiento de consultas escaladas sin respuesta (Fase 2, roadmap "motor
 * de respuesta con escalado al dueño"): {@code ESCALATED} que lleva más de
 * {@value #EXPIRY_HOURS}h sin que el dueño conteste pasa a {@code EXPIRED}
 * — el vecino no se queda esperando para siempre, y ops se entera con UN
 * aviso por corrida (mismo criterio que {@code
 * MerchantOfferExpiryScheduler#notifyMerchantOffersExpiringWithoutOwnerPush}:
 * un digest con todas las vencidas de esta pasada, no un mensaje por
 * consulta). Mismo patrón que los demás schedulers del repo: {@code Clock}
 * inyectable, apagable por property, {@code processOnce()} invocable desde
 * tests.
 */
@Service
public class BusinessInquiryExpiryScheduler {

  private static final Logger log = LoggerFactory.getLogger(BusinessInquiryExpiryScheduler.class);

  static final int EXPIRY_HOURS = 72;

  private final BusinessInquiryRepository businessInquiryRepository;
  private final BusinessRepository businessRepository;
  private final BusinessTimelineService businessTimelineService;
  private final TelegramNotifyService telegramNotifyService;
  private final boolean enabled;
  private final Clock clock;

  public BusinessInquiryExpiryScheduler(
      BusinessInquiryRepository businessInquiryRepository,
      BusinessRepository businessRepository,
      BusinessTimelineService businessTimelineService,
      TelegramNotifyService telegramNotifyService,
      @Value("${fixy.business-inquiries.expiry.enabled:true}") boolean enabled,
      Clock clock
  ) {
    this.businessInquiryRepository = businessInquiryRepository;
    this.businessRepository = businessRepository;
    this.businessTimelineService = businessTimelineService;
    this.telegramNotifyService = telegramNotifyService;
    this.enabled = enabled;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.business-inquiries.expiry.scheduler-fixed-delay-ms:3600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int expired = processOnce();
    if (expired > 0) {
      log.info("consultas escaladas vencidas (72h sin respuesta del dueño): {}", expired);
    }
  }

  /** Un ciclo del job, invocable directamente desde tests. Devuelve cuántas consultas se marcaron EXPIRED. */
  @Transactional
  public int processOnce() {
    OffsetDateTime cutoff = OffsetDateTime.now(clock).minusHours(EXPIRY_HOURS);
    List<BusinessInquiry> stale = businessInquiryRepository
        .findByStatusAndCreatedAtBefore(BusinessInquiryStatus.ESCALATED, cutoff);
    if (stale.isEmpty()) {
      return 0;
    }

    List<TelegramNotifyService.ExpiredBusinessInquiry> digest = new ArrayList<>();
    for (BusinessInquiry inquiry : stale) {
      inquiry.setStatus(BusinessInquiryStatus.EXPIRED);
      businessInquiryRepository.save(inquiry);
      businessTimelineService.appendEvent(inquiry.getBusinessId(), "INQUIRY_EXPIRED", "system", inquiry.getQuestion());
      businessRepository.findById(inquiry.getBusinessId())
          .ifPresent(business -> digest.add(new TelegramNotifyService.ExpiredBusinessInquiry(business, inquiry)));
    }

    try {
      telegramNotifyService.notifyBusinessInquiriesExpired(digest);
    } catch (Exception ex) {
      log.warn("aviso a ops de consultas vencidas falló: {}", ex.getMessage());
    }

    return stale.size();
  }
}
