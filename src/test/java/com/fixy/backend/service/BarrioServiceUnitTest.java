package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.BarrioReport;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class BarrioServiceUnitTest {

  private static BarrioReport report(long id, double lat, double lng) throws Exception {
    BarrioReport r = new BarrioReport();
    Field f = BarrioReport.class.getDeclaredField("id");
    f.setAccessible(true);
    f.set(r, id);
    r.setLat(lat);
    r.setLng(lng);
    return r;
  }

  private static final double LAT = -34.8270;
  private static final double LNG = -55.9330;

  @Test
  void dedupeEncuentraElReporteA20MetrosYNoElDe60() throws Exception {
    BarrioReport a20 = report(1, LAT, LNG - 0.0002); // ~18 m
    BarrioReport a60 = report(2, LAT, LNG - 0.0007); // ~64 m
    assertThat(BarrioService.nearestWithin(List.of(a60, a20), LAT, LNG, 40, null)).contains(a20);
    assertThat(BarrioService.nearestWithin(List.of(a60), LAT, LNG, 40, null)).isEmpty();
  }

  @Test
  void dedupeEligeElMasCercanoYIgnoraAlPropio() throws Exception {
    BarrioReport propio = report(10, LAT, LNG);
    BarrioReport cerca = report(11, LAT, LNG - 0.0001);
    BarrioReport lejos = report(12, LAT, LNG - 0.0003);
    assertThat(BarrioService.nearestWithin(List.of(propio, lejos, cerca), LAT, LNG, 40, 10L)).contains(cerca);
  }

  @Test
  void elLimiteDeCuarentaMetrosEsInclusivo() throws Exception {
    double d40 = 40.0 / 111_194.9; // grados de latitud para 40 m
    BarrioReport justo = report(1, LAT + d40, LNG);
    assertThat(BarrioGeo.haversineMeters(LAT, LNG, justo.getLat(), justo.getLng())).isLessThanOrEqualTo(40.0 + 0.01);
    assertThat(BarrioService.nearestWithin(List.of(justo), LAT, LNG, 40.05, null)).contains(justo);
    assertThat(BarrioService.nearestWithin(List.of(justo), LAT, LNG, 39.0, null)).isEmpty();
  }

  @Test
  void telefonoEnmascaradoDejaLosUltimosTresDigitos() {
    assertThat(BarrioService.maskPhone("59899429328")).isEqualTo("********328");
    assertThat(BarrioService.maskPhone(null)).isNull();
    assertThat(BarrioService.maskPhone(" ")).isNull();
  }

  @Test
  void categoriasValidas() {
    assertThat(BarrioService.requireCategory(" Bache ")).isEqualTo("bache");
    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.web.server.ResponseStatusException.class, () -> BarrioService.requireCategory("otra"));
  }

  @Test
  void etiquetas() {
    assertThat(BarrioService.labelOf("bache")).isEqualTo("Bache");
    assertThat(BarrioService.labelOf("poda")).isEqualTo("Restos de poda");
    assertThat(BarrioService.labelOf("luminaria")).isEqualTo("Luminaria apagada");
  }

  @Test
  void elRateLimitPorIpCortaConUn429() {
    BarrioService svc = new BarrioService(null, null, null, null, 2, 600);
    // categoría inválida: la validación corre DESPUÉS de contar la visita, así que gasta cupo.
    for (int i = 0; i < 2; i++) {
      org.junit.jupiter.api.Assertions.assertEquals(400, statusOf(() -> svc.createFromWeb("1.2.3.4", "x", null, null, null, null, null)));
    }
    org.junit.jupiter.api.Assertions.assertEquals(429, statusOf(() -> svc.createFromWeb("1.2.3.4", "x", null, null, null, null, null)));
    // otra IP tiene su propio cupo
    org.junit.jupiter.api.Assertions.assertEquals(400, statusOf(() -> svc.createFromWeb("5.6.7.8", "x", null, null, null, null, null)));
  }

  private static int statusOf(Runnable r) {
    try {
      r.run();
      return 200;
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return e.getStatusCode().value();
    }
  }
}
