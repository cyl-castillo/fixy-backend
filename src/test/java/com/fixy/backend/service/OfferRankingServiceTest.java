package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fixy.backend.model.Offer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Lógica PURA del ranking de conveniencia — sin Spring, sin H2, sin reloj
 * real: cada caso arma un {@link Offer} en memoria y un "now" fijo. Ver
 * javadoc de {@link OfferRankingService} para la tabla de señales/pesos que
 * estos tests verifican uno a uno, más los casos de desempate y el efecto
 * combinado.
 */
class OfferRankingServiceTest {

  private final OfferRankingService service = new OfferRankingService(new DiscountParser());
  private final OffsetDateTime now = OffsetDateTime.parse("2026-08-19T12:00:00Z");

  private Offer offer(long id) {
    Offer offer = new Offer();
    offer.setId(id);
    offer.setOrigin(Offer.ORIGIN_SCRAPED_SOURCE); // baseline neutro (0 pts) salvo que el caso lo cambie.
    offer.setCreatedAt(now.minusDays(30)); // baseline "vieja" (0 pts de frescura) salvo que el caso lo cambie.
    return offer;
  }

  // --- Urgencia ---

  @Test
  void sinValidUntilNoSumaUrgencia() {
    Offer offer = offer(1);
    assertThat(service.score(offer, now)).isZero();
  }

  @Test
  void venceDentroDe48hSumaTreinta() {
    Offer offer = offer(1);
    offer.setValidUntil(now.plusHours(47));
    assertThat(service.score(offer, now)).isEqualTo(30);
  }

  @Test
  void venceExactoALas48hSumaTreinta() {
    Offer offer = offer(1);
    offer.setValidUntil(now.plusHours(48));
    assertThat(service.score(offer, now)).isEqualTo(30);
  }

  @Test
  void venceUnMinutoDespuesDeLas48hSumaQuince() {
    Offer offer = offer(1);
    offer.setValidUntil(now.plusHours(48).plusMinutes(1));
    assertThat(service.score(offer, now)).isEqualTo(15);
  }

  @Test
  void venceDentroDeSieteDiasSumaQuince() {
    Offer offer = offer(1);
    offer.setValidUntil(now.plusDays(6));
    assertThat(service.score(offer, now)).isEqualTo(15);
  }

  @Test
  void venceExactoALosSieteDiasSumaQuince() {
    Offer offer = offer(1);
    offer.setValidUntil(now.plusDays(7));
    assertThat(service.score(offer, now)).isEqualTo(15);
  }

  @Test
  void venceDespuesDeSieteDiasNoSumaUrgencia() {
    Offer offer = offer(1);
    offer.setValidUntil(now.plusDays(7).plusMinutes(1));
    assertThat(service.score(offer, now)).isZero();
  }

  @Test
  void unaOfertaYaVencidaNoSumaUrgencia() {
    // Defensivo: listPublic ya filtra vencidas antes de rankear, pero el
    // score no debe premiar "vencida" como si fuera "última chance".
    Offer offer = offer(1);
    offer.setValidUntil(now.minusHours(1));
    assertThat(service.score(offer, now)).isZero();
  }

  // --- Descuento ---

  @Test
  void sinDiscountTextNoSumaNada() {
    Offer offer = offer(1);
    assertThat(service.score(offer, now)).isZero();
  }

  @Test
  void porcentajeSumaMitadDelNumeroConTope() {
    Offer offer = offer(1);
    offer.setDiscountText("20% off");
    assertThat(service.score(offer, now)).isEqualTo(10); // 20 * 0.5

    Offer offer45 = offer(2);
    offer45.setDiscountText("Hasta 45% de descuento");
    assertThat(service.score(offer45, now)).isCloseTo(22.5, within(0.001));
  }

  @Test
  void porcentajeAltoQuedaTopeadoAlSetenta() {
    Offer offer = offer(1);
    offer.setDiscountText("90% OFF liquidación total");
    assertThat(service.score(offer, now)).isEqualTo(35); // min(90,70) * 0.5
  }

  @Test
  void dosPorUnoSumaVeinticinco() {
    Offer offer = offer(1);
    offer.setDiscountText("Promo 2x1 en el local");
    assertThat(service.score(offer, now)).isEqualTo(25);
  }

  @Test
  void dosPorUnoEsCaseInsensitiveYToleraEspacios() {
    Offer offer = offer(1);
    offer.setDiscountText("2 X 1");
    assertThat(service.score(offer, now)).isEqualTo(25);
  }

  @Test
  void tresPorDosNoMatcheaLaSenalDeDosPorUno() {
    // El diseño pide literalmente "2x1", no un patrón genérico NxM.
    Offer offer = offer(1);
    offer.setDiscountText("3x2 en pastelería");
    assertThat(service.score(offer, now)).isZero();
  }

  @Test
  void graisOFreeSumaVeinte() {
    Offer gratis = offer(1);
    gratis.setDiscountText("Envío gratis en tu pedido");
    assertThat(service.score(gratis, now)).isEqualTo(20);

    Offer free = offer(2);
    free.setDiscountText("Delivery FREE hoy");
    assertThat(service.score(free, now)).isEqualTo(20);
  }

  @Test
  void senalesDeDescuentoSonAcumulables() {
    Offer offer = offer(1);
    offer.setDiscountText("20% off + 2x1 + envío gratis");
    assertThat(service.score(offer, now)).isEqualTo(10 + 25 + 20);
  }

  // --- Calidad ---

  @Test
  void tenerFotoSumaQuince() {
    Offer offer = offer(1);
    offer.setPhotoUrl("/uploads/offer-1/foo.jpg");
    assertThat(service.score(offer, now)).isEqualTo(15);
  }

  @Test
  void descripcionCortaNoSumaNada() {
    Offer offer = offer(1);
    offer.setDescription("a".repeat(59));
    assertThat(service.score(offer, now)).isZero();
  }

  @Test
  void descripcionDeSesentaCaracteresSumaDiez() {
    Offer offer = offer(1);
    offer.setDescription("a".repeat(60));
    assertThat(service.score(offer, now)).isEqualTo(10);
  }

  @Test
  void fotoYDescripcionLargaSonAcumulables() {
    Offer offer = offer(1);
    offer.setPhotoUrl("/uploads/offer-1/foo.jpg");
    offer.setDescription("a".repeat(80));
    assertThat(service.score(offer, now)).isEqualTo(25);
  }

  // --- Barrio primero (origen) ---

  @Test
  void origenManualSumaVeinticinco() {
    Offer offer = offer(1);
    offer.setOrigin(Offer.ORIGIN_MANUAL);
    assertThat(service.score(offer, now)).isEqualTo(25);
  }

  @Test
  void origenWhatsappForwardSumaVeinticinco() {
    Offer offer = offer(1);
    offer.setOrigin(Offer.ORIGIN_WHATSAPP_FORWARD);
    assertThat(service.score(offer, now)).isEqualTo(25);
  }

  @Test
  void origenScrapedSourceNoSumaNada() {
    Offer offer = offer(1);
    offer.setOrigin(Offer.ORIGIN_SCRAPED_SOURCE);
    assertThat(service.score(offer, now)).isZero();
  }

  // --- Frescura ---

  @Test
  void creadaHaceMenosDe72hSumaDiez() {
    Offer offer = offer(1);
    offer.setCreatedAt(now.minusHours(71));
    assertThat(service.score(offer, now)).isEqualTo(10);
  }

  @Test
  void creadaHaceExactoSetentaYDosHorasNoSumaFrescura() {
    Offer offer = offer(1);
    offer.setCreatedAt(now.minusHours(72));
    assertThat(service.score(offer, now)).isZero();
  }

  @Test
  void creadaHaceMasDe72hNoSumaFrescura() {
    Offer offer = offer(1);
    offer.setCreatedAt(now.minusHours(73));
    assertThat(service.score(offer, now)).isZero();
  }

  // --- Interacción (fase 3: señal real del barrio) ---

  @Test
  void sinInteraccionNoSumaNada() {
    Offer offer = offer(1);
    assertThat(service.score(offer, now)).isZero();
    assertThat(service.score(offer, now, 0)).isZero();
  }

  @Test
  void viewCountSumaPuntoDosPorVista() {
    Offer offer = offer(1);
    offer.setViewCount(20);
    assertThat(service.score(offer, now, 0)).isCloseTo(4.0, within(0.001)); // 20 * 0.2
  }

  @Test
  void inquiryCountSumaCincoPorConsulta() {
    Offer offer = offer(1);
    assertThat(service.score(offer, now, 3)).isEqualTo(15); // 3 * 5.0
  }

  @Test
  void likeCountSumaDosPorLike() {
    Offer offer = offer(1);
    offer.setLikeCount(4);
    assertThat(service.score(offer, now, 0)).isEqualTo(8); // 4 * 2.0
  }

  @Test
  void lasTresSenalesDeInteraccionSonAcumulablesPorDebajoDelTope() {
    Offer offer = offer(1);
    offer.setViewCount(10); // +2.0
    offer.setLikeCount(3); // +6.0
    assertThat(service.score(offer, now, 1)).isCloseTo(13.0, within(0.001)); // 2 + 5 + 6
  }

  @Test
  void interaccionQuedaTopeadaAVeinticinco() {
    Offer offer = offer(1);
    offer.setViewCount(1000); // 200 puntos crudos
    offer.setLikeCount(100); // 200 puntos crudos
    assertThat(service.score(offer, now, 50)).isEqualTo(25); // 200+250+200 topeado a 25
  }

  @Test
  void interaccionTopeadaNuncaLePasaAlBonusDeBarrio() {
    // Documenta la decisión: el tope de interacción (+25) queda al mismo
    // nivel que el bonus de barrio (+25, ver originScore) — una oferta
    // scrapeada híper-popular no puede, solo por interacción, superar a una
    // local equivalente que además sume su +25 de origen.
    Offer popularScraped = offer(1);
    popularScraped.setOrigin(Offer.ORIGIN_SCRAPED_SOURCE);
    popularScraped.setViewCount(100_000);
    popularScraped.setLikeCount(10_000);

    Offer localSinInteraccion = offer(2);
    localSinInteraccion.setOrigin(Offer.ORIGIN_MANUAL);

    assertThat(service.score(popularScraped, now, 10_000)).isEqualTo(25);
    assertThat(service.score(localSinInteraccion, now)).isEqualTo(25);
  }

  @Test
  void rankPropagaElInquiryCountDelMapaPorOfferId() {
    Offer conConsultas = offer(1);
    Offer sinConsultas = offer(2);
    // Mismo score base (0) — la única diferencia es inquiryCount vía mapa.

    List<Offer> ranked = service.rank(
        List.of(sinConsultas, conConsultas), now, Map.of(1L, 10));

    assertThat(ranked).containsExactly(conConsultas, sinConsultas);
  }

  @Test
  void rankSinMapaTrataTodoInquiryCountComoCero() {
    Offer a = offer(1);
    Offer b = offer(2);
    a.setCreatedAt(now.minusDays(10));
    b.setCreatedAt(now.minusDays(1));

    List<Offer> ranked = service.rank(List.of(a, b), now);

    assertThat(ranked).containsExactly(b, a); // desempate por createdAt, no por interacción.
  }

  // --- Score combinado ---

  @Test
  void ofertaLocalCompletaSumaTodasLasSenales() {
    Offer offer = offer(1);
    offer.setOrigin(Offer.ORIGIN_WHATSAPP_FORWARD);
    offer.setValidUntil(now.plusHours(10)); // +30
    offer.setDiscountText("30% off"); // +15
    offer.setPhotoUrl("/uploads/offer-1/foo.jpg"); // +15
    offer.setDescription("a".repeat(80)); // +10
    offer.setCreatedAt(now.minusHours(1)); // +10

    assertThat(service.score(offer, now)).isEqualTo(30 + 15 + 15 + 10 + 25 + 10);
  }

  // --- Desempate ---

  @Test
  void aIgualScoreGanaLaMasNuevaPorCreatedAt() {
    Offer vieja = offer(1);
    vieja.setCreatedAt(now.minusDays(10));
    Offer nueva = offer(2);
    nueva.setCreatedAt(now.minusDays(1));
    // now.minusDays(1) sigue > 72h así que ninguna suma frescura — mismo score (0) por otra vía.

    List<Offer> ranked = service.rank(List.of(vieja, nueva), now);

    assertThat(ranked).containsExactly(nueva, vieja);
  }

  @Test
  void aIgualScoreYCreatedAtGanaElIdMasAlto() {
    OffsetDateTime sameInstant = now.minusDays(10);
    Offer id1 = offer(1);
    id1.setCreatedAt(sameInstant);
    Offer id2 = offer(2);
    id2.setCreatedAt(sameInstant);

    List<Offer> ranked = service.rank(List.of(id1, id2), now);

    assertThat(ranked).containsExactly(id2, id1);
  }

  @Test
  void elOrdenTotalEsScoreDescLuegoCreatedAtDescLuegoIdDesc() {
    Offer bajoScore = offer(1);
    bajoScore.setCreatedAt(now.minusDays(1)); // score 0

    Offer altoScore = offer(2);
    altoScore.setOrigin(Offer.ORIGIN_MANUAL);
    altoScore.setValidUntil(now.plusHours(5)); // score 55

    Offer scoreMedio = offer(3);
    scoreMedio.setDiscountText("2x1"); // score 25

    List<Offer> ranked = service.rank(List.of(bajoScore, altoScore, scoreMedio), now);

    assertThat(ranked).containsExactly(altoScore, scoreMedio, bajoScore);
  }

  @Test
  void rankNoMutaLaListaDeEntrada() {
    Offer a = offer(1);
    Offer b = offer(2);
    List<Offer> input = List.of(a, b); // inmutable — falla si rank() intenta ordenar in-place.

    List<Offer> ranked = service.rank(input, now);

    assertThat(ranked).hasSize(2);
  }

  // --- Caso de negocio central: local vs. scrapeada equivalente ---

  @Test
  void unaOfertaScrapedNuevaNoLeGanaAUnaLocalEquivalente() {
    OffsetDateTime sameValidUntil = now.plusDays(10);
    String sameDiscount = "20% off";

    Offer local = offer(1);
    local.setOrigin(Offer.ORIGIN_MANUAL);
    local.setValidUntil(sameValidUntil);
    local.setDiscountText(sameDiscount);
    local.setCreatedAt(now.minusHours(20)); // fresca también (+10)

    Offer scrapedMasNueva = offer(2);
    scrapedMasNueva.setOrigin(Offer.ORIGIN_SCRAPED_SOURCE);
    scrapedMasNueva.setValidUntil(sameValidUntil);
    scrapedMasNueva.setDiscountText(sameDiscount);
    scrapedMasNueva.setCreatedAt(now.minusMinutes(1)); // más fresca que la local, e igual no alcanza.

    List<Offer> ranked = service.rank(List.of(scrapedMasNueva, local), now);

    assertThat(ranked).containsExactly(local, scrapedMasNueva);
  }
}
