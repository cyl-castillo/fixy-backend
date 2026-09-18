package com.fixy.backend.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tier 2 (contrato §A.1): ventanas de disponibilidad del proveedor, formato
 * plano {@code lun=08-20;mar=08-20;...}. Null/vacío = siempre disponible.
 */
class AvailabilityWindowsTest {

  private static final ZoneId MONTEVIDEO = ZoneId.of("America/Montevideo");

  private ZonedDateTime mondayAt(int hour, int minute) {
    // 2026-09-14 es lunes.
    return ZonedDateTime.of(2026, 9, 14, hour, minute, 0, 0, MONTEVIDEO);
  }

  private ZonedDateTime saturdayAt(int hour, int minute) {
    // 2026-09-19 es sábado.
    return ZonedDateTime.of(2026, 9, 19, hour, minute, 0, 0, MONTEVIDEO);
  }

  @Test
  void nullOVacioEsSiempreDisponible() {
    assertThat(AvailabilityWindows.parse(null).isOpenAt(mondayAt(3, 0))).isTrue();
    assertThat(AvailabilityWindows.parse("").isOpenAt(saturdayAt(23, 59))).isTrue();
    assertThat(AvailabilityWindows.parse("   ").isOpenAt(mondayAt(0, 0))).isTrue();
  }

  @Test
  void isOpenAtDentroDelRangoDeclarado() {
    AvailabilityWindows w = AvailabilityWindows.parse("lun=08-20;mar=08-20;mie=08-20;jue=08-20;vie=08-20;sab=09-13;dom=");
    assertThat(w.isOpenAt(mondayAt(8, 0))).isTrue();
    assertThat(w.isOpenAt(mondayAt(19, 59))).isTrue();
    assertThat(w.isOpenAt(mondayAt(12, 30))).isTrue();
  }

  @Test
  void isOpenAtFueraDelRangoDeclarado() {
    AvailabilityWindows w = AvailabilityWindows.parse("lun=08-20");
    assertThat(w.isOpenAt(mondayAt(7, 59))).isFalse();
    // Límite exclusivo: a las 20:00 en punto ya cerró.
    assertThat(w.isOpenAt(mondayAt(20, 0))).isFalse();
    assertThat(w.isOpenAt(mondayAt(21, 0))).isFalse();
  }

  @Test
  void diaAusenteONotDeclaradoEsNoDisponibleEseDia() {
    AvailabilityWindows w = AvailabilityWindows.parse("lun=08-20;sab=09-13;dom=");
    assertThat(w.isOpenAt(saturdayAt(10, 0))).isTrue();
    // Domingo con "dom=" (rango vacío): explícitamente no disponible.
    ZonedDateTime sunday = saturdayAt(10, 0).plusDays(1);
    assertThat(w.isOpenAt(sunday)).isFalse();
    // Martes ni siquiera está en la cadena: también cerrado.
    ZonedDateTime tuesday = mondayAt(10, 0).plusDays(1);
    assertThat(w.isOpenAt(tuesday)).isFalse();
  }

  @Test
  void formatIdaYVuelta() {
    String raw = "lun=08-20;mar=08-20;mie=08-20;jue=08-20;vie=08-20;sab=09-13;dom=";
    AvailabilityWindows w = AvailabilityWindows.parse(raw);
    String formatted = w.format();
    assertThat(formatted).isEqualTo(raw);
    // Re-parsear el formato produce el mismo comportamiento.
    AvailabilityWindows reparsed = AvailabilityWindows.parse(formatted);
    assertThat(reparsed.isOpenAt(mondayAt(10, 0))).isTrue();
    assertThat(reparsed.isOpenAt(saturdayAt(14, 0))).isFalse();
  }

  @Test
  void formatSiempreDisponibleEsCadenaVacia() {
    assertThat(AvailabilityWindows.parse(null).format()).isEmpty();
    assertThat(AvailabilityWindows.parse("").format()).isEmpty();
  }

  @Test
  void formatoInvalidoTiraBadRequestConMensajeClaro() {
    assertThatThrownBy(() -> AvailabilityWindows.parse("lunes=08-20"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("día inválido");
    assertThatThrownBy(() -> AvailabilityWindows.parse("lun=8-20-30"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> AvailabilityWindows.parse("lun=20-08"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("menor a la de cierre");
    assertThatThrownBy(() -> AvailabilityWindows.parse("lun=25-30"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> AvailabilityWindows.parse("noesundia=08-20"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> AvailabilityWindows.parse("sinigual"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void nextOpeningSiempreDisponibleDevuelveElMismoInstante() {
    AvailabilityWindows w = AvailabilityWindows.parse(null);
    ZonedDateTime now = mondayAt(3, 0);
    assertThat(w.nextOpening(now)).isEqualTo(now);
  }

  @Test
  void nextOpeningYaAbiertoDevuelveElMismoInstante() {
    AvailabilityWindows w = AvailabilityWindows.parse("lun=08-20");
    ZonedDateTime now = mondayAt(10, 0);
    assertThat(w.nextOpening(now)).isEqualTo(now);
  }

  @Test
  void nextOpeningMismoDiaAntesDeAbrir() {
    AvailabilityWindows w = AvailabilityWindows.parse("lun=08-20");
    ZonedDateTime beforeOpening = mondayAt(5, 0);
    ZonedDateTime expected = mondayAt(8, 0);
    assertThat(w.nextOpening(beforeOpening)).isEqualTo(expected);
  }

  @Test
  void nextOpeningSaltaAlProximoDiaDisponible() {
    AvailabilityWindows w = AvailabilityWindows.parse("sab=09-13");
    // Lunes: el próximo día disponible es el sábado siguiente.
    ZonedDateTime monday = mondayAt(10, 0);
    ZonedDateTime nextOpening = w.nextOpening(monday);
    assertThat(nextOpening.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SATURDAY);
    assertThat(nextOpening.getHour()).isEqualTo(9);
  }
}
