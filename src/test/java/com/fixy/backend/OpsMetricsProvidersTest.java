package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.dto.OpsDailyMetricsResponse;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderOffer;
import com.fixy.backend.model.ProviderOfferContext;
import com.fixy.backend.model.ProviderOfferResponse;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.ProviderOfferRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.OpsMetricsService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tier 3 (contrato §A.3/§A.4): desglose de {@code provider_offers} del
 * rango (offerResponses, medianOfferResponseMinutes) y el ranking de
 * proveedores por velocidad de respuesta ("Mis números" a nivel ops).
 * Semilla de proveedores apagada: los 6 proveedores hardcodeados de
 * ProviderSeedConfig harían el ranking impredecible.
 */
@SpringBootTest
@TestPropertySource(properties = "fixy.seed.providers=false")
@Transactional
class OpsMetricsProvidersTest {

  @Autowired private OpsMetricsService opsMetricsService;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private ProviderOfferRepository providerOfferRepository;

  private static final OffsetDateTime WINDOW_FROM = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
  private static final OffsetDateTime WINDOW_TO = OffsetDateTime.of(2026, 6, 8, 0, 0, 0, 0, ZoneOffset.UTC);

  private Provider createProvider(String name, String categories, ProviderStatus status) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone("0" + Math.abs(name.hashCode() % 100000000));
    provider.setCategories(categories);
    provider.setStatus(status);
    return providerRepository.save(provider);
  }

  private void createOffer(Provider provider, Long leadId, OffsetDateTime offeredAt,
      OffsetDateTime respondedAt, ProviderOfferResponse response, boolean inWindow) {
    ProviderOffer offer = new ProviderOffer();
    offer.setLeadId(leadId);
    offer.setProviderId(provider.getId());
    offer.setContext(ProviderOfferContext.INITIAL);
    offer.setOfferedAt(offeredAt);
    offer.setRespondedAt(respondedAt);
    offer.setResponse(response);
    offer.setInWindow(inWindow);
    providerOfferRepository.save(offer);
  }

  @Test
  void ranksProvidersByMedianResponseMinutesAscendingWithNullsLast() {
    OffsetDateTime base = WINDOW_FROM.plusDays(1);

    Provider fast = createProvider("Fontanero Rapido Ranking", "plomeria", ProviderStatus.AVAILABLE);
    createOffer(fast, 9001L, base, base.plusMinutes(5), ProviderOfferResponse.ACCEPTED, true);
    createOffer(fast, 9002L, base.plusHours(1), base.plusHours(1).plusMinutes(7), ProviderOfferResponse.ACCEPTED, true);

    Provider slow = createProvider("Fontanero Lento Ranking", "plomeria", ProviderStatus.AVAILABLE);
    createOffer(slow, 9003L, base, base.plusMinutes(40), ProviderOfferResponse.DECLINED, true);

    // NEW (no está AVAILABLE) pero con una oferta en el rango: entra igual
    // por tener actividad, aunque sin muestra respondida (mediana null).
    Provider newcomer = createProvider("Fontanero Nuevo Ranking", "plomeria", ProviderStatus.NEW);
    createOffer(newcomer, 9004L, base, null, null, true);

    // AVAILABLE sin ninguna oferta en el rango -> aparece igual (activo),
    // con mediana null.
    Provider idle = createProvider("Fontanero Sin Ofertas Ranking", "plomeria", ProviderStatus.AVAILABLE);

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);

    List<Long> ourIds = List.of(fast.getId(), slow.getId(), newcomer.getId(), idle.getId());
    List<OpsDailyMetricsResponse.ProviderResponseStats> ours = metrics.providers().stream()
        .filter(p -> ourIds.contains(p.id()))
        .toList();

    assertThat(ours).hasSize(4);
    assertThat(ours.get(0).id()).isEqualTo(fast.getId());
    assertThat(ours.get(0).medianResponseMinutes()).isEqualTo(6);
    assertThat(ours.get(1).id()).isEqualTo(slow.getId());
    assertThat(ours.get(1).medianResponseMinutes()).isEqualTo(40);
    // Los dos sin muestra respondida quedan al final, en cualquier orden entre sí.
    assertThat(ours.get(2).medianResponseMinutes()).isNull();
    assertThat(ours.get(3).medianResponseMinutes()).isNull();

    OpsDailyMetricsResponse.ProviderResponseStats fastStats = ours.get(0);
    assertThat(fastStats.offers()).isEqualTo(2);
    assertThat(fastStats.accepted()).isEqualTo(2);
    assertThat(fastStats.declined()).isZero();
    assertThat(fastStats.acceptanceRatePercentage()).isEqualTo(100.0);
    assertThat(fastStats.openNow()).isTrue(); // sin availabilityWindows declarado -> siempre disponible.
    assertThat(fastStats.availabilityWindows()).isNull();

    OpsDailyMetricsResponse.ProviderResponseStats slowStats = ours.get(1);
    assertThat(slowStats.declined()).isEqualTo(1);
    assertThat(slowStats.acceptanceRatePercentage()).isEqualTo(0.0);
  }

  @Test
  void offerResponsesAndMedianOfferResponseMinutesSummarizeOffersInRange() {
    OffsetDateTime base = WINDOW_FROM.plusDays(2);
    Provider provider = createProvider("Fontanero Resumen Ofertas", "plomeria", ProviderStatus.AVAILABLE);

    createOffer(provider, 9101L, base, base.plusMinutes(10), ProviderOfferResponse.ACCEPTED, true);
    createOffer(provider, 9102L, base, base.plusMinutes(20), ProviderOfferResponse.DECLINED, true);
    createOffer(provider, 9103L, base, null, ProviderOfferResponse.TIMEOUT, true);
    createOffer(provider, 9104L, base, null, null, true);
    // Fuera de ventana: no debe entrar en la mediana aunque esté aceptada.
    createOffer(provider, 9105L, base, base.plusMinutes(999), ProviderOfferResponse.ACCEPTED, false);
    // Fuera del rango pedido (offeredAt antes de WINDOW_FROM): no debe contarse.
    createOffer(provider, 9106L, WINDOW_FROM.minusDays(1), WINDOW_FROM.minusDays(1).plusMinutes(2),
        ProviderOfferResponse.ACCEPTED, true);

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);
    OpsDailyMetricsResponse.OfferResponses offerResponses = metrics.offerResponses();

    assertThat(offerResponses.total()).isEqualTo(5);
    assertThat(offerResponses.accepted()).isEqualTo(2);
    assertThat(offerResponses.declined()).isEqualTo(1);
    assertThat(offerResponses.timeout()).isEqualTo(1);
    assertThat(offerResponses.pending()).isEqualTo(1);
    assertThat(offerResponses.inWindow()).isEqualTo(4);

    // Mediana solo sobre EN VENTANA + respondidas (ACCEPTED/DECLINED) dentro
    // del rango: [10, 20] -> 15.
    assertThat(metrics.medianOfferResponseMinutes()).isEqualTo(15);
  }
}
