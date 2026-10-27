package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class BarrioGeoTest {

  @Test
  void haversineDeUnMilesimoDeGradoDeLatitudSonUnos111Metros() {
    double d = BarrioGeo.haversineMeters(-34.8270, -55.9330, -34.8260, -55.9330);
    assertThat(d).isCloseTo(111.2, within(0.5));
  }

  @Test
  void haversineEsSimetricaYCeroParaElMismoPunto() {
    assertThat(BarrioGeo.haversineMeters(-34.83, -55.95, -34.83, -55.95)).isZero();
    double ab = BarrioGeo.haversineMeters(-34.83, -55.95, -34.81, -55.90);
    double ba = BarrioGeo.haversineMeters(-34.81, -55.90, -34.83, -55.95);
    assertThat(ab).isCloseTo(ba, within(0.001));
  }

  @Test
  void veinteMetrosEnLongitudQuedanDentroDeLosCuarentaMetros() {
    // 0.0002 grados de longitud a -34.83 son ~18 m.
    double d = BarrioGeo.haversineMeters(-34.8270, -55.9330, -34.8270, -55.9332);
    assertThat(d).isBetween(15.0, 25.0);
  }

  @Test
  void cajaDeCoberturaIncluyeLosBordesYExcluyeAfuera() {
    assertThat(BarrioGeo.inCoverage(-34.82, -55.95)).isTrue();
    assertThat(BarrioGeo.inCoverage(-34.90, -56.10)).isTrue();
    assertThat(BarrioGeo.inCoverage(-34.74, -55.80)).isTrue();
    assertThat(BarrioGeo.inCoverage(-34.9001, -55.95)).isFalse(); // Montevideo centro
    assertThat(BarrioGeo.inCoverage(-34.90, -56.1001)).isFalse();
    assertThat(BarrioGeo.inCoverage(-34.739, -55.95)).isFalse();
    assertThat(BarrioGeo.inCoverage(-34.82, -55.799)).isFalse();
    assertThat(BarrioGeo.inCoverage(-34.9033, -56.1882)).isFalse(); // Montevideo (Plaza Independencia)
  }

  @Test
  void elYamlCargaTodosLosBarriosYTodosCaenDentroDeLaCaja() {
    BarrioGeo.Zones zones = BarrioGeo.load();
    assertThat(zones.zones()).extracting(BarrioGeo.Zone::name)
        .contains("Shangrilá", "San José de Carrasco", "Lagomar", "Solymar", "Lomas de Solymar",
            "Montes de Solymar", "Médanos de Solymar", "Parque de Solymar", "El Pinar",
            "Lomas de Carrasco", "Colinas de Solymar", "Pinamar");
    assertThat(zones.maxDistanceKm()).isEqualTo(2.5);
    assertThat(zones.defaultZone()).isEqualTo("Ciudad de la Costa");
    assertThat(zones.zones()).allSatisfy(z -> assertThat(BarrioGeo.inCoverage(z.lat(), z.lng())).isTrue());
  }

  @Test
  void elBarrioEsElCentroideMasCercano() {
    BarrioGeo.Zones zones = BarrioGeo.load();
    BarrioGeo.Zone lagomar = zones.zones().stream().filter(z -> z.name().equals("Lagomar")).findFirst().orElseThrow();
    assertThat(BarrioGeo.zoneFor(lagomar.lat() + 0.001, lagomar.lng() - 0.001, zones)).isEqualTo("Lagomar");
    BarrioGeo.Zone pinar = zones.zones().stream().filter(z -> z.name().equals("El Pinar")).findFirst().orElseThrow();
    assertThat(BarrioGeo.zoneFor(pinar.lat(), pinar.lng(), zones)).isEqualTo("El Pinar");
  }

  @Test
  void aMasDeDosKilometrosYMedioDelCentroideMasCercanoEsCiudadDeLaCosta() {
    BarrioGeo.Zones zones = new BarrioGeo.Zones(
        java.util.List.of(new BarrioGeo.Zone("Lagomar", -34.8270, -55.9330)), 2.5, "Ciudad de la Costa");
    // ~2.2 km al norte: todavía es Lagomar
    assertThat(BarrioGeo.zoneFor(-34.8270 + 0.0198, -55.9330, zones)).isEqualTo("Lagomar");
    // ~2.8 km al norte: ya no
    assertThat(BarrioGeo.zoneFor(-34.8270 + 0.0252, -55.9330, zones)).isEqualTo("Ciudad de la Costa");
  }
}
