package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fixy.backend.dto.OfferDigestSendResponse;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.model.PushSubscription;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import com.fixy.backend.repository.PushSubscriptionRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ventana del digest automático (Fase Push-2): {@link OfferDigestAutoScheduler}
 * dispara {@link OfferDigestService#send()} solo día/hora exactos en
 * America/Montevideo, y avisa a ops SOLO si mandó algo (dataset de este test
 * tiene menos de 3 ofertas vigentes en cualquier zona real, así que el
 * digest en sí manda 0 — lo que se prueba acá es exclusivamente el gate de
 * ventana, no el contenido del envío).
 */
@SpringBootTest
class OfferDigestAutoSchedulerTest {

  private static final ZoneId MONTEVIDEO = ZoneId.of("America/Montevideo");

  @Autowired private OfferDigestService offerDigestService;
  @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
  @Autowired private BusinessRepository businessRepository;
  @Autowired private OfferRepository offerRepository;

  private OfferDigestAutoScheduler scheduler(TelegramNotifyService telegram, Clock clock, boolean enabled) {
    return new OfferDigestAutoScheduler(offerDigestService, telegram, enabled, "THURSDAY", 18, clock);
  }

  private Clock clockAt(int year, int month, int day, int hour) {
    ZonedDateTime zdt = ZonedDateTime.of(year, month, day, hour, 0, 0, 0, MONTEVIDEO);
    return Clock.fixed(zdt.toInstant(), MONTEVIDEO);
  }

  @Test
  void diaYHoraCorrectos_disparaElDigest() {
    // 2026-08-27 es jueves.
    Clock thursday18 = clockAt(2026, 8, 27, 18);
    TelegramNotifyService telegram = mock(TelegramNotifyService.class);

    OfferDigestSendResponse response = scheduler(telegram, thursday18, true).processOnce();

    assertThat(response).as("dentro de la ventana, corre").isNotNull();
  }

  @Test
  void diaCorrectoHoraIncorrecta_noDispara() {
    Clock thursday17 = clockAt(2026, 8, 27, 17);
    TelegramNotifyService telegram = mock(TelegramNotifyService.class);

    OfferDigestSendResponse response = scheduler(telegram, thursday17, true).processOnce();

    assertThat(response).as("fuera de la hora configurada, no corre").isNull();
    verify(telegram, never()).notifyAutoDigestSummary(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void horaCorrectaDiaIncorrecto_noDispara() {
    // 2026-08-26 es miércoles.
    Clock wednesday18 = clockAt(2026, 8, 26, 18);
    TelegramNotifyService telegram = mock(TelegramNotifyService.class);

    OfferDigestSendResponse response = scheduler(telegram, wednesday18, true).processOnce();

    assertThat(response).as("fuera del día configurado, no corre").isNull();
  }

  @Test
  void deshabilitado_runNoTocaNadaAunEnLaVentana() {
    Clock thursday18 = clockAt(2026, 8, 27, 18);
    TelegramNotifyService telegram = mock(TelegramNotifyService.class);

    scheduler(telegram, thursday18, false).run();

    verify(telegram, never()).notifyAutoDigestSummary(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @Transactional
  void enLaVentana_conAlMenosUnEnvio_avisaAOpsPorTelegram() {
    // Zona dedicada de este test con 3 ofertas vigentes + una suscripción
    // elegible, para forzar sent > 0 y ejercitar el aviso a ops.
    Clock thursday18 = clockAt(2026, 8, 27, 18);
    TelegramNotifyService telegram = mock(TelegramNotifyService.class);
    String zone = "Parque Miramar";

    Business business = new Business();
    business.setName("Comercio Digest Auto Test");
    business.setWhatsappNumber("098500010");
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    businessRepository.save(business);

    for (String title : new String[] {"20% off", "2x1", "$500 fijo"}) {
      Offer offer = new Offer();
      offer.setBusinessId(business.getId());
      offer.setTitle(title);
      offer.setCategory("otro");
      offer.setZone(zone);
      offer.setStatus(OfferStatus.ACTIVE);
      offer.setValidUntil(OffsetDateTime.now().plusDays(30));
      offerRepository.save(offer);
    }

    PushSubscription sub = new PushSubscription();
    sub.setEndpoint("https://digest-auto-test.example/ep-910020");
    sub.setP256dh("dummy-p256dh-digest-auto-test");
    sub.setAuth("dummy-auth-digest-auto-test");
    sub.setZone(zone);
    pushSubscriptionRepository.save(sub);

    OfferDigestSendResponse response = scheduler(telegram, thursday18, true).processOnce();

    assertThat(response).isNotNull();
    assertThat(response.sent()).as("la suscripción de este test es elegible, con 3 ofertas vigentes en su zona").isGreaterThanOrEqualTo(1);
    verify(telegram).notifyAutoDigestSummary(response);
  }
}
