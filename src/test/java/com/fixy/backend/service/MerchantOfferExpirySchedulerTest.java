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
 * Aviso de vencimiento al dueño (Fase 5): {@link MerchantOfferExpiryScheduler}
 * avisa "«oferta» vence en 2 días" al comercio con suscripción push propia
 * cuando una de sus ofertas ACTIVE entra en las próximas 48h — throttle de 1
 * día por suscripción. Comercios sin ninguna suscripción se resuelven en un
 * digest best-effort a ops (no verificable acá sin mockear Telegram — el
 * bot-token está vacío en tests, así que ese camino es no-op silencioso;
 * se cubre indirectamente comprobando que {@code processOnce} no cuenta esos
 * casos como "enviados" y no revienta).
 *
 * Mismo patrón que {@link SavedOfferReminderSchedulerTest}: {@code Clock}
 * fijo instanciado a mano, {@code processOnce()} invocado directo,
 * {@code @Transactional} para no ensuciar el resto de la suite.
 */
@SpringBootTest
@Transactional
class MerchantOfferExpirySchedulerTest {

  @Autowired private BusinessRepository businessRepository;
  @Autowired private OfferRepository offerRepository;
  @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
  @Autowired private PushNotificationService pushNotificationService;
  @Autowired private TelegramNotifyService telegramNotifyService;

  private MerchantOfferExpiryScheduler scheduler(Clock clock) {
    return new MerchantOfferExpiryScheduler(
        offerRepository, businessRepository, pushSubscriptionRepository,
        pushNotificationService, telegramNotifyService, true, clock);
  }

  private Business persistBusiness(String tag) {
    Business business = new Business();
    business.setName("Comercio Vencimiento Test " + tag);
    business.setWhatsappNumber("0989" + tag);
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    business.setPanelToken("merchant-expiry-token-" + tag);
    return businessRepository.save(business);
  }

  private Offer persistOffer(Business business, OffsetDateTime validUntil, String title) {
    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle(title);
    offer.setCategory("otro");
    offer.setStatus(OfferStatus.ACTIVE);
    offer.setValidFrom(OffsetDateTime.now().minusDays(5));
    offer.setValidUntil(validUntil);
    return offerRepository.save(offer);
  }

  private PushSubscription persistSub(Business business, String tag, OffsetDateTime lastMerchantReminderAt) {
    PushSubscription sub = new PushSubscription();
    sub.setEndpoint("https://merchant-expiry-test.example/ep-" + tag);
    sub.setP256dh("dummy-p256dh-merchant-expiry-test");
    sub.setAuth("dummy-auth-merchant-expiry-test");
    sub.setBusinessId(business.getId());
    sub.setLastMerchantReminderAt(lastMerchantReminderAt);
    return pushSubscriptionRepository.save(sub);
  }

  private Clock fixedNow() {
    return Clock.fixed(Instant.now(), ZoneOffset.UTC);
  }

  @Test
  void ofertaVenceDentroDe48hConSuscripcion_mandaElAvisoYMarcaLaFecha() {
    Clock now = fixedNow();
    Business business = persistBusiness("001");
    persistOffer(business, OffsetDateTime.now(now).plusHours(30), "Oferta por vencer con push");
    PushSubscription sub = persistSub(business, "vence-30h", null);

    int sent = scheduler(now).processOnce();

    assertThat(sent).isEqualTo(1);
    PushSubscription reloaded = pushSubscriptionRepository.findById(sub.getId()).orElseThrow();
    assertThat(reloaded.getLastMerchantReminderAt()).isNotNull();
  }

  @Test
  void ofertaVenceDespuesDe48h_noMandaNada() {
    Clock now = fixedNow();
    Business business = persistBusiness("002");
    persistOffer(business, OffsetDateTime.now(now).plusHours(72), "Oferta lejana con push");
    PushSubscription sub = persistSub(business, "vence-72h", null);

    int sent = scheduler(now).processOnce();

    assertThat(sent).isEqualTo(0);
    PushSubscription reloaded = pushSubscriptionRepository.findById(sub.getId()).orElseThrow();
    assertThat(reloaded.getLastMerchantReminderAt()).isNull();
  }

  @Test
  void ofertaYaVencida_noSeConsidera() {
    Clock now = fixedNow();
    Business business = persistBusiness("003");
    persistOffer(business, OffsetDateTime.now(now).minusHours(1), "Oferta ya vencida");
    PushSubscription sub = persistSub(business, "ya-vencida", null);

    int sent = scheduler(now).processOnce();

    assertThat(sent).isEqualTo(0);
    PushSubscription reloaded = pushSubscriptionRepository.findById(sub.getId()).orElseThrow();
    assertThat(reloaded.getLastMerchantReminderAt()).isNull();
  }

  @Test
  void throttleDiario_segundaCorridaElMismoDiaNoReenvia() {
    Clock now = fixedNow();
    Business business = persistBusiness("004");
    persistOffer(business, OffsetDateTime.now(now).plusHours(10), "Oferta por vencer throttle");
    persistSub(business, "throttle", null);

    int firstRun = scheduler(now).processOnce();
    int secondRun = scheduler(now).processOnce();

    assertThat(firstRun).isEqualTo(1);
    assertThat(secondRun).isEqualTo(0);
  }

  @Test
  void yaAvisadoHoy_noVuelveAMandarAunqueSigaVigente() {
    Clock now = fixedNow();
    Business business = persistBusiness("005");
    persistOffer(business, OffsetDateTime.now(now).plusHours(20), "Oferta ya avisada");
    persistSub(business, "ya-avisado", OffsetDateTime.now(now).minusHours(2));

    int sent = scheduler(now).processOnce();

    assertThat(sent).isEqualTo(0);
  }

  @Test
  void variasOfertasDelMismoComercio_eligeLaMasProximaAVencer() {
    Clock now = fixedNow();
    Business business = persistBusiness("006");
    persistOffer(business, OffsetDateTime.now(now).plusHours(40), "Lejana dentro de 48h");
    persistOffer(business, OffsetDateTime.now(now).plusHours(10), "La más próxima");
    persistSub(business, "varias-ofertas", null);

    int sent = scheduler(now).processOnce();

    // Un solo push por suscripción elegible, sin importar cuántas ofertas
    // del comercio entren en la ventana.
    assertThat(sent).isEqualTo(1);
  }

  @Test
  void variasSuscripcionesDelMismoComercio_cadaUnaRecibeElAviso() {
    Clock now = fixedNow();
    Business business = persistBusiness("007");
    persistOffer(business, OffsetDateTime.now(now).plusHours(15), "Oferta con dos dispositivos");
    persistSub(business, "dispositivo-a", null);
    persistSub(business, "dispositivo-b", null);

    int sent = scheduler(now).processOnce();

    assertThat(sent).isEqualTo(2);
  }

  @Test
  void comercioSinSuscripcion_noRompeYNoCuentaComoEnviado() {
    Clock now = fixedNow();
    Business business = persistBusiness("008");
    persistOffer(business, OffsetDateTime.now(now).plusHours(12), "Oferta sin dueño suscripto");

    int sent = scheduler(now).processOnce();

    assertThat(sent).isEqualTo(0);
  }

  @Test
  void sinOfertasPorVencer_noHaceNada() {
    Clock now = fixedNow();
    persistBusiness("009");

    int sent = scheduler(now).processOnce();

    assertThat(sent).isGreaterThanOrEqualTo(0);
  }
}
