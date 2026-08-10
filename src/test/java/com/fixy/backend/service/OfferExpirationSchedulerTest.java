package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Historia 3.4: active → expired cuando pasa validUntil. Mismo patrón que
 * los demás schedulers (processOnce() invocado directo desde el test, sin
 * esperar al @Scheduled real). Cada aserción filtra por los ids creados en
 * ESTE test (lección conocida del repo: no asumir tabla vacía — H2
 * compartida entre contextos cacheados).
 */
@SpringBootTest
@Transactional
class OfferExpirationSchedulerTest {

  @Autowired private OfferExpirationScheduler scheduler;
  @Autowired private OfferRepository offerRepository;
  @Autowired private BusinessRepository businessRepository;

  private Business persistBusiness(String whatsapp) {
    Business business = new Business();
    business.setName("Comercio Test Expiración");
    business.setWhatsappNumber(whatsapp);
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    return businessRepository.save(business);
  }

  private Offer persistOffer(Business business, OfferStatus status, OffsetDateTime validUntil) {
    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle("Oferta test expiración");
    offer.setCategory("otro");
    offer.setStatus(status);
    offer.setValidUntil(validUntil);
    return offerRepository.save(offer);
  }

  @Test
  void ofertaActivaVencidaPasaAExpired() {
    Business business = persistBusiness("098333001");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().minusDays(1));

    int expired = scheduler.processOnce();

    assertThat(expired).isEqualTo(1);
    Offer reloaded = offerRepository.findById(offer.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(OfferStatus.EXPIRED);
  }

  @Test
  void ofertaActivaVigenteNoExpira() {
    Business business = persistBusiness("098333002");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    int expired = scheduler.processOnce();

    Offer reloaded = offerRepository.findById(offer.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(OfferStatus.ACTIVE);
    // No afirmamos expired==0 en términos absolutos (puede haber otras
    // ofertas activas vencidas de otro test en la misma transacción/base
    // compartida) — solo que ESTA oferta no fue tocada.
    assertThat(expired).isGreaterThanOrEqualTo(0);
  }

  @Test
  void draftORejectedConValidUntilPasadoNuncaExpiranSolos() {
    Business business = persistBusiness("098333003");
    Offer draft = persistOffer(business, OfferStatus.DRAFT, OffsetDateTime.now().minusDays(1));
    Offer rejected = persistOffer(business, OfferStatus.REJECTED, OffsetDateTime.now().minusDays(1));

    scheduler.processOnce();

    assertThat(offerRepository.findById(draft.getId()).orElseThrow().getStatus()).isEqualTo(OfferStatus.DRAFT);
    assertThat(offerRepository.findById(rejected.getId()).orElseThrow().getStatus()).isEqualTo(OfferStatus.REJECTED);
  }

  @Test
  void ofertaActivaSinValidUntilNuncaExpiraSolaAunSiendoUnEstadoQueNoDeberiaExistir() {
    // Defensivo: el diseño garantiza (vía OfferService.approve) que ACTIVE
    // siempre tiene validUntil, pero el scheduler no debe reventar ni
    // expirar por accidente si alguna vez esa invariante se rompe.
    Business business = persistBusiness("098333004");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, null);

    scheduler.processOnce();

    assertThat(offerRepository.findById(offer.getId()).orElseThrow().getStatus()).isEqualTo(OfferStatus.ACTIVE);
  }

  @Test
  void esIdempotente() {
    Business business = persistBusiness("098333005");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().minusHours(1));

    int firstRun = scheduler.processOnce();
    int secondRun = scheduler.processOnce();

    assertThat(firstRun).isEqualTo(1);
    assertThat(secondRun).isEqualTo(0);
    assertThat(offerRepository.findById(offer.getId()).orElseThrow().getStatus()).isEqualTo(OfferStatus.EXPIRED);
  }

  @Test
  void unaOfertaExpiradaDejaDeEstarEnElListadoDeActivasYPasaAlDeExpiradas() {
    Business business = persistBusiness("098333006");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().minusMinutes(5));

    scheduler.processOnce();

    List<Long> activeIds = offerRepository.findByStatusOrderByCreatedAtDesc(OfferStatus.ACTIVE)
        .stream().map(Offer::getId).toList();
    List<Long> expiredIds = offerRepository.findByStatusOrderByCreatedAtDesc(OfferStatus.EXPIRED)
        .stream().map(Offer::getId).toList();

    assertThat(activeIds).doesNotContain(offer.getId());
    assertThat(expiredIds).contains(offer.getId());
  }
}
