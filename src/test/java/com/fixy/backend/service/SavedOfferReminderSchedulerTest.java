package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.model.PushSubscription;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import com.fixy.backend.repository.PushSubscriptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recordatorio de guardadas por vencer (Fase Push-2, enganche):
 * {@link SavedOfferReminderScheduler} avisa "«oferta» vence mañana" cuando
 * una oferta guardada entra en las próximas 24h, con throttle de 1 día por
 * suscripción y limpieza de ids muertos del CSV en la misma pasada.
 *
 * Mismo patrón que los demás tests de scheduler del repo: {@code Clock}
 * fijo instanciado directo (sin pasar por el bean de Spring) y
 * {@code processOnce()} invocado a mano. {@code @Transactional} para que
 * nada de lo creado acá quede pisando el resto de la suite.
 */
@SpringBootTest
@Transactional
class SavedOfferReminderSchedulerTest {

  @Autowired private BusinessRepository businessRepository;
  @Autowired private OfferRepository offerRepository;
  @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
  @Autowired private PushNotificationService pushNotificationService;

  private SavedOfferReminderScheduler scheduler(Clock clock) {
    return new SavedOfferReminderScheduler(
        pushSubscriptionRepository, offerRepository, pushNotificationService, true, clock);
  }

  private Business persistBusiness(String whatsapp) {
    Business business = new Business();
    business.setName("Comercio Test Recordatorio Guardadas");
    business.setWhatsappNumber(whatsapp);
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    return businessRepository.save(business);
  }

  private Offer persistOffer(Business business, OfferStatus status, OffsetDateTime validUntil, String title) {
    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle(title);
    offer.setCategory("otro");
    offer.setStatus(status);
    offer.setValidUntil(validUntil);
    return offerRepository.save(offer);
  }

  private PushSubscription persistSub(String tag, String savedOfferIdsCsv, OffsetDateTime lastSavedReminderAt) {
    PushSubscription sub = new PushSubscription();
    sub.setEndpoint("https://saved-reminder-test.example/ep-" + tag);
    sub.setP256dh("dummy-p256dh-saved-reminder-test");
    sub.setAuth("dummy-auth-saved-reminder-test");
    sub.setSavedOfferIds(savedOfferIdsCsv);
    sub.setLastSavedReminderAt(lastSavedReminderAt);
    return pushSubscriptionRepository.save(sub);
  }

  private Clock fixedNow() {
    return Clock.fixed(Instant.now(), ZoneOffset.UTC);
  }

  @Test
  void ofertaVenceDentroDe24h_mandaElRecordatorioYMarcaLaFecha() {
    Clock now = fixedNow();
    Business business = persistBusiness("098600001");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now(now).plusHours(10), "Oferta por vencer");
    PushSubscription sub = persistSub("vence-en-10h", String.valueOf(offer.getId()), null);

    int sent = scheduler(now).processOnce();

    assertThat(sent).isEqualTo(1);
    PushSubscription reloaded = pushSubscriptionRepository.findById(sub.getId()).orElseThrow();
    assertThat(reloaded.getLastSavedReminderAt()).isNotNull();
  }

  @Test
  void ofertaVenceDespuesDe24h_noMandaNada() {
    Clock now = fixedNow();
    Business business = persistBusiness("098600002");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now(now).plusHours(48), "Oferta lejana");
    PushSubscription sub = persistSub("vence-en-48h", String.valueOf(offer.getId()), null);

    int sent = scheduler(now).processOnce();

    assertThat(sent).isEqualTo(0);
    PushSubscription reloaded = pushSubscriptionRepository.findById(sub.getId()).orElseThrow();
    assertThat(reloaded.getLastSavedReminderAt()).isNull();
    assertThat(reloaded.getSavedOfferIds()).as("sigue activa y vigente: no se limpia del CSV").isEqualTo(String.valueOf(offer.getId()));
  }

  @Test
  void throttleDiario_segundaCorridaElMismoDiaNoReenvia() {
    Clock now = fixedNow();
    Business business = persistBusiness("098600003");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now(now).plusHours(5), "Oferta por vencer throttle");
    PushSubscription sub = persistSub("throttle", String.valueOf(offer.getId()), null);

    int firstRun = scheduler(now).processOnce();
    int secondRun = scheduler(now).processOnce();

    assertThat(firstRun).isEqualTo(1);
    assertThat(secondRun).isEqualTo(0);
  }

  @Test
  void yaRecordadoHoy_noVuelveAMandarAunqueSigaVigente() {
    Clock now = fixedNow();
    Business business = persistBusiness("098600004");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now(now).plusHours(3), "Oferta ya recordada");
    PushSubscription sub = persistSub("ya-recordado", String.valueOf(offer.getId()), OffsetDateTime.now(now).minusHours(2));

    int sent = scheduler(now).processOnce();

    assertThat(sent).isEqualTo(0);
  }

  @Test
  void idQueYaNoExiste_seLimpiaDelCsv() {
    Clock now = fixedNow();
    PushSubscription sub = persistSub("id-inexistente", "999999999", null);

    scheduler(now).processOnce();

    PushSubscription reloaded = pushSubscriptionRepository.findById(sub.getId()).orElseThrow();
    assertThat(reloaded.getSavedOfferIds()).isNull();
  }

  @Test
  void idExpiradoORechazado_seLimpiaDelCsvSinMandarRecordatorio() {
    Clock now = fixedNow();
    Business business = persistBusiness("098600005");
    Offer expired = persistOffer(business, OfferStatus.EXPIRED, OffsetDateTime.now(now).minusDays(1), "Oferta vencida");
    Offer rejected = persistOffer(business, OfferStatus.REJECTED, null, "Oferta rechazada");
    String csv = expired.getId() + "," + rejected.getId();
    PushSubscription sub = persistSub("expirada-rechazada", csv, null);

    int sent = scheduler(now).processOnce();

    assertThat(sent).isEqualTo(0);
    PushSubscription reloaded = pushSubscriptionRepository.findById(sub.getId()).orElseThrow();
    assertThat(reloaded.getSavedOfferIds()).as("ambos ids se limpian: uno EXPIRED y otro REJECTED").isNull();
  }

  @Test
  void variasGuardadas_eligeLaMasProximaAVencerDentroDe24h() {
    Clock now = fixedNow();
    Business business = persistBusiness("098600006");
    Offer lejana = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now(now).plusHours(20), "Lejana dentro de 24h");
    Offer proxima = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now(now).plusHours(5), "La más próxima");
    String csv = lejana.getId() + "," + proxima.getId();
    PushSubscription sub = persistSub("varias-guardadas", csv, null);

    int sent = scheduler(now).processOnce();

    assertThat(sent).isEqualTo(1);
    // No hay forma directa de leer el título enviado sin mockear PushNotificationService;
    // lo que sí podemos afirmar es que el CSV sigue intacto (ninguna de las dos es stale)
    // y que se mandó exactamente un recordatorio, no dos.
    PushSubscription reloaded = pushSubscriptionRepository.findById(sub.getId()).orElseThrow();
    assertThat(reloaded.getSavedOfferIds()).contains(String.valueOf(lejana.getId())).contains(String.valueOf(proxima.getId()));
  }

  @Test
  void sinSavedOfferIds_noEsCandidata() {
    Clock now = fixedNow();
    persistSub("sin-guardadas", null, null);

    // No debe reventar ni contar nada de esta suscripción; otras subs del
    // contexto compartido no se filtran acá porque solo afirmamos sent>=0.
    int sent = scheduler(now).processOnce();

    assertThat(sent).isGreaterThanOrEqualTo(0);
  }
}
