package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessInquiry;
import com.fixy.backend.model.BusinessInquiryStatus;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.repository.BusinessInquiryRepository;
import com.fixy.backend.repository.BusinessRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vencimiento de consultas escaladas sin respuesta (Fase 2): {@link
 * BusinessInquiryExpiryScheduler} marca {@code EXPIRED} las {@code
 * ESCALATED} de más de 72h. Mismo patrón que {@code
 * PendingProviderApprovalSchedulerTest}: {@code createdAt} es {@code
 * updatable=false} (no se puede retro-datar), así que se corre el RELOJ
 * hacia adelante en vez de backdatear la fila. El aviso a ops por Telegram
 * no es verificable acá sin mockear el bot (token vacío en test = no-op
 * silencioso) — se cubre indirectamente comprobando que la corrida no
 * revienta y cuenta bien las expiradas.
 */
@SpringBootTest
@Transactional
class BusinessInquiryExpirySchedulerTest {

  @Autowired private BusinessRepository businessRepository;
  @Autowired private BusinessInquiryRepository businessInquiryRepository;
  @Autowired private BusinessTimelineService businessTimelineService;
  @Autowired private TelegramNotifyService telegramNotifyService;

  private final SecureRandom random = new SecureRandom();

  private BusinessInquiryExpiryScheduler scheduler(Clock clock) {
    return new BusinessInquiryExpiryScheduler(
        businessInquiryRepository, businessRepository, businessTimelineService, telegramNotifyService, true, clock);
  }

  /** Reloj corrido hacia adelante: los createdAt recién escritos no se pueden retro-datar. */
  private Clock inHours(long hours) {
    return Clock.fixed(Instant.now().plus(Duration.ofHours(hours)), ZoneOffset.UTC);
  }

  private Business persistBusiness(String tag) {
    Business business = new Business();
    business.setName("Comercio Inquiry Expiry Test " + tag);
    business.setWhatsappNumber("0966" + tag);
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    business.setPanelToken("inquiry-expiry-token-" + tag);
    return businessRepository.save(business);
  }

  private BusinessInquiry persistInquiry(Business business, BusinessInquiryStatus status) {
    BusinessInquiry inquiry = new BusinessInquiry();
    inquiry.setBusinessId(business.getId());
    inquiry.setQuestion("Pregunta de expiry test " + business.getId() + "-" + random.nextInt(1_000_000));
    inquiry.setStatus(status);
    inquiry.setAccessToken(randomToken());
    return businessInquiryRepository.save(inquiry);
  }

  private String randomToken() {
    byte[] buf = new byte[16];
    random.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }

  @Test
  void escaladaHaceMasDe72h_pasaAExpired() {
    Business business = persistBusiness("001");
    BusinessInquiry inquiry = persistInquiry(business, BusinessInquiryStatus.ESCALATED);

    int expired = scheduler(inHours(73)).processOnce();

    assertThat(expired).isEqualTo(1);
    assertThat(businessInquiryRepository.findById(inquiry.getId()).orElseThrow().getStatus())
        .isEqualTo(BusinessInquiryStatus.EXPIRED);
  }

  @Test
  void escaladaHaceMenosDe72h_noSeToca() {
    Business business = persistBusiness("002");
    BusinessInquiry inquiry = persistInquiry(business, BusinessInquiryStatus.ESCALATED);

    int expired = scheduler(inHours(10)).processOnce();

    assertThat(expired).isEqualTo(0);
    assertThat(businessInquiryRepository.findById(inquiry.getId()).orElseThrow().getStatus())
        .isEqualTo(BusinessInquiryStatus.ESCALATED);
  }

  @Test
  void yaAnsweredOwnerAunqueSeaVieja_noSeToca() {
    Business business = persistBusiness("003");
    BusinessInquiry inquiry = persistInquiry(business, BusinessInquiryStatus.ANSWERED_OWNER);

    int expired = scheduler(inHours(100)).processOnce();

    assertThat(expired).isEqualTo(0);
    assertThat(businessInquiryRepository.findById(inquiry.getId()).orElseThrow().getStatus())
        .isEqualTo(BusinessInquiryStatus.ANSWERED_OWNER);
  }

  @Test
  void variasEscaladasVencidas_pasanTodasAExpiredEnUnaCorrida() {
    Business business = persistBusiness("004");
    BusinessInquiry a = persistInquiry(business, BusinessInquiryStatus.ESCALATED);
    BusinessInquiry b = persistInquiry(business, BusinessInquiryStatus.ESCALATED);

    int expired = scheduler(inHours(80)).processOnce();

    assertThat(expired).isEqualTo(2);
    assertThat(businessInquiryRepository.findById(a.getId()).orElseThrow().getStatus())
        .isEqualTo(BusinessInquiryStatus.EXPIRED);
    assertThat(businessInquiryRepository.findById(b.getId()).orElseThrow().getStatus())
        .isEqualTo(BusinessInquiryStatus.EXPIRED);
  }

  @Test
  void segundaCorridaNoReprocesaLasYaExpiradas() {
    Business business = persistBusiness("005");
    persistInquiry(business, BusinessInquiryStatus.ESCALATED);
    Clock later = inHours(80);

    int firstRun = scheduler(later).processOnce();
    int secondRun = scheduler(later).processOnce();

    assertThat(firstRun).isEqualTo(1);
    assertThat(secondRun).isEqualTo(0);
  }
}
