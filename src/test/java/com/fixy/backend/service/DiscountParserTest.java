package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Parsing puro de {@code discountText} — sin Spring, sin H2 (ver javadoc de
 * {@link DiscountParser}).
 */
class DiscountParserTest {

  private final DiscountParser parser = new DiscountParser();

  // --- extractPercent ---

  @Test
  void extractPercentDevuelveElNumeroAntesDelSigno() {
    assertThat(parser.extractPercent("20%")).isEqualTo(20);
    assertThat(parser.extractPercent("20% off")).isEqualTo(20);
    assertThat(parser.extractPercent("Hasta 30% de descuento")).isEqualTo(30);
  }

  @Test
  void extractPercentNullSinPatron() {
    assertThat(parser.extractPercent("2x1")).isNull();
    assertThat(parser.extractPercent("Envío gratis")).isNull();
    assertThat(parser.extractPercent("$500")).isNull();
    assertThat(parser.extractPercent(null)).isNull();
    assertThat(parser.extractPercent("")).isNull();
  }

  // --- hasTwoForOne / hasThreeForTwo ---

  @Test
  void hasTwoForOneDetectaVariantesDeEspacioYMayusculas() {
    assertThat(parser.hasTwoForOne("2x1")).isTrue();
    assertThat(parser.hasTwoForOne("2 X 1")).isTrue();
    assertThat(parser.hasTwoForOne("Promo 2x1 en el local")).isTrue();
    assertThat(parser.hasTwoForOne("3x2")).isFalse();
    assertThat(parser.hasTwoForOne(null)).isFalse();
  }

  @Test
  void hasThreeForTwoDetectaVariantesDeEspacioYMayusculas() {
    assertThat(parser.hasThreeForTwo("3x2")).isTrue();
    assertThat(parser.hasThreeForTwo("3 X 2")).isTrue();
    assertThat(parser.hasThreeForTwo("3x2 en pastelería")).isTrue();
    assertThat(parser.hasThreeForTwo("2x1")).isFalse();
    assertThat(parser.hasThreeForTwo(null)).isFalse();
  }

  // --- hasFree ---

  @Test
  void hasFreeDetectaGratisYFreeCaseInsensitive() {
    assertThat(parser.hasFree("Envío gratis en tu pedido")).isTrue();
    assertThat(parser.hasFree("Delivery FREE hoy")).isTrue();
    assertThat(parser.hasFree("20% off")).isFalse();
    assertThat(parser.hasFree(null)).isFalse();
  }

  // --- discountPercent (consolidado, para OfferAnalysisService) ---

  @Test
  void discountPercentConPorcentajeExplicito() {
    assertThat(parser.discountPercent("20%")).isEqualTo(20);
    assertThat(parser.discountPercent("20% off")).isEqualTo(20);
    assertThat(parser.discountPercent("Hasta 30% de descuento")).isEqualTo(30);
  }

  @Test
  void discountPercentDosPorUnoEsCincuenta() {
    assertThat(parser.discountPercent("2x1")).isEqualTo(50);
    assertThat(parser.discountPercent("Promo 2x1 en el local")).isEqualTo(50);
  }

  @Test
  void discountPercentTresPorDosEsTreintaYTres() {
    assertThat(parser.discountPercent("3x2")).isEqualTo(33);
    assertThat(parser.discountPercent("3x2 en pastelería")).isEqualTo(33);
  }

  @Test
  void discountPercentGratisNoEsComparable() {
    assertThat(parser.discountPercent("gratis")).isNull();
    assertThat(parser.discountPercent("Envío gratis")).isNull();
    assertThat(parser.discountPercent("Delivery FREE hoy")).isNull();
  }

  @Test
  void discountPercentMontoFijoNoEsComparable() {
    assertThat(parser.discountPercent("$500")).isNull();
    assertThat(parser.discountPercent("$500 fijo")).isNull();
  }

  @Test
  void discountPercentNullOVacioNoEsComparable() {
    assertThat(parser.discountPercent(null)).isNull();
    assertThat(parser.discountPercent("")).isNull();
    assertThat(parser.discountPercent("   ")).isNull();
  }

  @Test
  void discountPercentSinPatronConocidoNoEsComparable() {
    assertThat(parser.discountPercent("Promoción especial")).isNull();
  }

  @Test
  void discountPercentPriorizaElPorcentajeExplicitoSobreDosPorUno() {
    // Texto combinado: el porcentaje literal es la señal más confiable, gana.
    assertThat(parser.discountPercent("2x1 + 10% en la segunda unidad")).isEqualTo(10);
  }
}
