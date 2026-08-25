package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fixy.backend.dto.OfferAnalysis;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.OfferRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Lógica de {@link OfferAnalysisService} — la única dependencia externa
 * ({@link OfferRepository}) se mockea (mismo patrón que
 * {@code ProviderCatalogServiceRankingTest}): esto es lógica de análisis
 * pura sobre el historial que la query devuelve, no hace falta Spring ni H2.
 */
@ExtendWith(MockitoExtension.class)
class OfferAnalysisServiceTest {

  @Mock
  private OfferRepository offerRepository;

  private final DiscountParser discountParser = new DiscountParser();
  private OfferAnalysisService service;

  private final OffsetDateTime now = OffsetDateTime.parse("2026-08-25T12:00:00Z");

  private void service() {
    service = new OfferAnalysisService(offerRepository, discountParser);
  }

  private Offer offer(long id, String category, String discountText, OffsetDateTime createdAt) {
    Offer offer = new Offer();
    offer.setId(id);
    offer.setCategory(category);
    offer.setDiscountText(discountText);
    offer.setCreatedAt(createdAt);
    offer.setStatus(OfferStatus.ACTIVE);
    offer.setValidUntil(now.plusDays(10));
    return offer;
  }

  private void mockHistory(List<Offer> history) {
    when(offerRepository.findByCategoryInAndStatusIn(anyList(), anyList())).thenReturn(history);
  }

  // --- analyze: batch vacío ---

  @Test
  void analyzeDeListaVaciaDevuelveMapaVacioSinConsultarElRepositorio() {
    service();
    Map<Long, OfferAnalysis> result = service.analyze(List.of(), now);
    assertThat(result).isEmpty();
    verify(offerRepository, times(0)).findByCategoryInAndStatusIn(any(), any());
  }

  // --- discountPercent propagado ---

  @Test
  void discountPercentPropagaElDelParser() {
    service();
    Offer a = offer(1, "pasteleria", "20% off", now.minusDays(5));
    mockHistory(List.of(a));

    Map<Long, OfferAnalysis> result = service.analyze(List.of(a), now);

    assertThat(result.get(1L).discountPercent()).isEqualTo(20);
  }

  @Test
  void discountPercentNullCuandoElTextoNoEsComparable() {
    service();
    Offer a = offer(1, "pasteleria", "Envío gratis", now.minusDays(5));
    mockHistory(List.of(a));

    Map<Long, OfferAnalysis> result = service.analyze(List.of(a), now);

    assertThat(result.get(1L).discountPercent()).isNull();
  }

  // --- bestOfCategory ---

  @Test
  void bestOfCategoryFalseSiEsLaUnicaVigenteDeSuCategoria() {
    service();
    Offer unica = offer(1, "jardineria-cat-unica", "50% off", now.minusDays(5));
    mockHistory(List.of(unica));

    Map<Long, OfferAnalysis> result = service.analyze(List.of(unica), now);

    assertThat(result.get(1L).bestOfCategory()).isFalse();
  }

  @Test
  void bestOfCategoryEligeElMayorDescuento() {
    service();
    Offer bajo = offer(1, "pasteleria-cat-mayor", "10% off", now.minusDays(5));
    Offer alto = offer(2, "pasteleria-cat-mayor", "40% off", now.minusDays(3));
    mockHistory(List.of(bajo, alto));

    Map<Long, OfferAnalysis> result = service.analyze(List.of(bajo, alto), now);

    assertThat(result.get(2L).bestOfCategory()).isTrue();
    assertThat(result.get(1L).bestOfCategory()).isFalse();
  }

  @Test
  void bestOfCategoryEnEmpateGanaLaMasVieja() {
    service();
    Offer vieja = offer(1, "pasteleria-cat-empate", "20% off", now.minusDays(10));
    Offer nueva = offer(2, "pasteleria-cat-empate", "20% off", now.minusDays(1));
    mockHistory(List.of(nueva, vieja)); // orden de entrada no debería importar.

    Map<Long, OfferAnalysis> result = service.analyze(List.of(vieja, nueva), now);

    assertThat(result.get(1L).bestOfCategory()).isTrue(); // la vieja.
    assertThat(result.get(2L).bestOfCategory()).isFalse();
  }

  @Test
  void bestOfCategoryFalseParaTodasSiNingunaTieneDescuentoComparable() {
    service();
    Offer a = offer(1, "servicios-cat-sin-comparables", "Envío gratis", now.minusDays(5));
    Offer b = offer(2, "servicios-cat-sin-comparables", "$500 fijo", now.minusDays(3));
    mockHistory(List.of(a, b));

    Map<Long, OfferAnalysis> result = service.analyze(List.of(a, b), now);

    assertThat(result.get(1L).bestOfCategory()).isFalse();
    assertThat(result.get(2L).bestOfCategory()).isFalse();
  }

  @Test
  void bestOfCategoryIgnoraOfertasNoVigentesDelHistorialAlElegirGanador() {
    service();
    Offer vigenteBaja = offer(1, "otro-cat-no-vigentes", "10% off", now.minusDays(5));
    Offer expiredAlta = offer(2, "otro-cat-no-vigentes", "90% off", now.minusDays(20));
    expiredAlta.setStatus(OfferStatus.EXPIRED);
    Offer vigenteMedia = offer(3, "otro-cat-no-vigentes", "30% off", now.minusDays(2));
    mockHistory(List.of(vigenteBaja, expiredAlta, vigenteMedia));

    Map<Long, OfferAnalysis> result = service.analyze(List.of(vigenteBaja, vigenteMedia), now);

    // Solo hay 2 VIGENTES en la categoría (la expired no cuenta ni para el
    // tamaño mínimo ni como candidata a ganadora) — gana la de 30%, no la
    // expired de 90%.
    assertThat(result.get(3L).bestOfCategory()).isTrue();
    assertThat(result.get(1L).bestOfCategory()).isFalse();
  }

  // --- firstTimeInWeeks ---

  @Test
  void firstTimeInWeeksNullSinDiscountPercent() {
    service();
    Offer sinDescuento = offer(1, "cat-sin-descuento", null, now.minusDays(5));
    mockHistory(List.of(sinDescuento));

    Map<Long, OfferAnalysis> result = service.analyze(List.of(sinDescuento), now);

    assertThat(result.get(1L).firstTimeInWeeks()).isNull();
  }

  @Test
  void firstTimeInWeeksNullSinHistorialPrevio() {
    service();
    Offer sola = offer(1, "cat-sin-historial", "20% off", now.minusDays(5));
    mockHistory(List.of(sola));

    Map<Long, OfferAnalysis> result = service.analyze(List.of(sola), now);

    assertThat(result.get(1L).firstTimeInWeeks()).isNull();
  }

  @Test
  void firstTimeInWeeksNullSiElAntecedenteEsDeMenosDeUnaSemana() {
    service();
    OffsetDateTime creadaHoy = now.minusDays(1);
    Offer antecedenteReciente = offer(1, "cat-antecedente-reciente", "20% off", creadaHoy.minusDays(3));
    antecedenteReciente.setStatus(OfferStatus.EXPIRED);
    Offer actual = offer(2, "cat-antecedente-reciente", "20% off", creadaHoy);
    mockHistory(List.of(antecedenteReciente, actual));

    Map<Long, OfferAnalysis> result = service.analyze(List.of(actual), now);

    assertThat(result.get(2L).firstTimeInWeeks()).isNull();
  }

  @Test
  void firstTimeInWeeksCuentaSemanasCompletasDesdeElUltimoAntecedente() {
    service();
    OffsetDateTime actualCreatedAt = now.minusDays(1);
    Offer antecedente = offer(1, "cat-tres-semanas", "25% off", actualCreatedAt.minusDays(21)); // 3 semanas antes
    antecedente.setStatus(OfferStatus.EXPIRED);
    Offer actual = offer(2, "cat-tres-semanas", "20% off", actualCreatedAt); // >= el antecedente (25 >= 20)
    mockHistory(List.of(antecedente, actual));

    Map<Long, OfferAnalysis> result = service.analyze(List.of(actual), now);

    assertThat(result.get(2L).firstTimeInWeeks()).isEqualTo(3);
  }

  @Test
  void firstTimeInWeeksIgnoraAntecedentesConDescuentoMenor() {
    service();
    OffsetDateTime actualCreatedAt = now.minusDays(1);
    Offer antecedenteDebil = offer(1, "cat-antecedente-debil", "10% off", actualCreatedAt.minusDays(21));
    antecedenteDebil.setStatus(OfferStatus.EXPIRED);
    Offer actual = offer(2, "cat-antecedente-debil", "20% off", actualCreatedAt);
    mockHistory(List.of(antecedenteDebil, actual));

    Map<Long, OfferAnalysis> result = service.analyze(List.of(actual), now);

    // El único antecedente tiene menos descuento (10 < 20) — no cuenta como
    // "ya vimos un descuento así de bueno", así que no hay historial útil.
    assertThat(result.get(2L).firstTimeInWeeks()).isNull();
  }

  @Test
  void firstTimeInWeeksQuedaTopeadoADoce() {
    service();
    OffsetDateTime actualCreatedAt = now.minusDays(1);
    Offer antecedenteMuyViejo = offer(1, "cat-tope-doce", "20% off", actualCreatedAt.minusDays(365));
    antecedenteMuyViejo.setStatus(OfferStatus.EXPIRED);
    Offer actual = offer(2, "cat-tope-doce", "20% off", actualCreatedAt);
    mockHistory(List.of(antecedenteMuyViejo, actual));

    Map<Long, OfferAnalysis> result = service.analyze(List.of(actual), now);

    assertThat(result.get(2L).firstTimeInWeeks()).isEqualTo(12);
  }

  @Test
  void firstTimeInWeeksUsaElAntecedenteMasReciente() {
    service();
    OffsetDateTime actualCreatedAt = now.minusDays(1);
    Offer antecedenteViejo = offer(1, "cat-antecedente-mas-reciente", "20% off", actualCreatedAt.minusDays(60)); // ~8 semanas
    antecedenteViejo.setStatus(OfferStatus.EXPIRED);
    Offer antecedenteReciente = offer(2, "cat-antecedente-mas-reciente", "20% off", actualCreatedAt.minusDays(14)); // 2 semanas
    antecedenteReciente.setStatus(OfferStatus.EXPIRED);
    Offer actual = offer(3, "cat-antecedente-mas-reciente", "20% off", actualCreatedAt);
    mockHistory(List.of(antecedenteViejo, antecedenteReciente, actual));

    Map<Long, OfferAnalysis> result = service.analyze(List.of(actual), now);

    assertThat(result.get(3L).firstTimeInWeeks()).isEqualTo(2); // el más reciente, no el más viejo.
  }

  // --- costo: una sola query aunque el batch tenga varias categorías/ofertas ---

  @Test
  void analyzeHaceUnaSolaQueryDeHistorialParaTodoElBatch() {
    service();
    Offer a = offer(1, "costo-cat-uno", "20% off", now.minusDays(5));
    Offer b = offer(2, "costo-cat-dos", "30% off", now.minusDays(3));
    Offer c = offer(3, "costo-cat-uno", "15% off", now.minusDays(2));
    mockHistory(List.of(a, b, c));

    service.analyze(List.of(a, b, c), now);

    verify(offerRepository, times(1)).findByCategoryInAndStatusIn(anyList(), anyList());
  }
}
