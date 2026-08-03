package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.CoverageZone;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * El mapa de Fixy, sin Spring: la jerarquía "Ciudad de la Costa contiene a los
 * barrios" y la consistencia del catálogo único de zonas.
 *
 * <p>Motivación (embudo de prod, 2026-08-03): el catálogo de zonas estaba
 * escrito a mano en 5 lugares y ya se había desincronizado — "Montes de
 * Solymar" faltaba en {@code LeadService}. Ver el javadoc de
 * {@link CoverageZone}.
 */
class CoverageZoneHierarchyTest {

  @Test
  void ciudadDeLaCostaEsElParaguasDeTodosLosBarrios() {
    List<CoverageZone> barrios =
        List.of(CoverageZone.values()).stream()
            .filter(zone -> zone != CoverageZone.CIUDAD_DE_LA_COSTA)
            .toList();

    assertThat(barrios).isNotEmpty();
    assertThat(barrios)
        .allSatisfy(zone ->
            assertThat(zone.parent()).contains(CoverageZone.CIUDAD_DE_LA_COSTA));
    assertThat(CoverageZone.CIUDAD_DE_LA_COSTA.parent()).isEmpty();
  }

  /** Dirección 1: el proveedor declara la ciudad entera, el pedido nombra un barrio. */
  @Test
  void quienCubreLaCiudadEnteraCubreCadaBarrio() {
    assertThat(CoverageZone.covers("Ciudad de la Costa", "Lagomar")).isTrue();
    assertThat(CoverageZone.covers("Ciudad de la Costa", "El Pinar")).isTrue();
    assertThat(CoverageZone.covers("Ciudad de la Costa", "Montes de Solymar")).isTrue();
  }

  /** Dirección 2: el pedido llega con la zona genérica, el proveedor declara su barrio. */
  @Test
  void elPedidoGenericoAlcanzaAlProveedorDeUnBarrio() {
    assertThat(CoverageZone.covers("Solymar", "Ciudad de la Costa")).isTrue();
    assertThat(CoverageZone.covers("Aeroparque", "Ciudad de la Costa")).isTrue();
  }

  /** La jerarquía no debe volver "todo matchea con todo": dos barrios distintos siguen sin verse. */
  @Test
  void dosBarriosDistintosNoSeCubrenEntreSi() {
    assertThat(CoverageZone.covers("Solymar", "Lagomar")).isFalse();
    assertThat(CoverageZone.covers("El Pinar", "Aeroparque")).isFalse();
  }

  @Test
  void zonasFueraDeCoberturaSiguenCayendoAIgualdadExacta() {
    assertThat(CoverageZone.covers("Pocitos", "Pocitos")).isTrue();
    assertThat(CoverageZone.covers("Ciudad de la Costa", "Pocitos")).isFalse();
    assertThat(CoverageZone.covers("Pocitos", "Ciudad de la Costa")).isFalse();
    assertThat(CoverageZone.isCovered("Pocitos")).isFalse();
    assertThat(CoverageZone.isCovered("Cordón")).isFalse();
  }

  @Test
  void zonaVaciaNuncaCubre() {
    assertThat(CoverageZone.covers(null, "Solymar")).isFalse();
    assertThat(CoverageZone.covers("Solymar", null)).isFalse();
    assertThat(CoverageZone.covers("  ", "Solymar")).isFalse();
  }

  @Test
  void acentosYMayusculasDanLaMismaZona() {
    assertThat(CoverageZone.covers("Shangrila", "Shangrilá")).isTrue();
    assertThat(CoverageZone.covers("SOLYMAR", "solymar")).isTrue();
    assertThat(CoverageZone.fromLabel("san jose de carrasco"))
        .contains(CoverageZone.SAN_JOSE_DE_CARRASCO);
    assertThat(CoverageZone.isCovered("Shangrilá")).isTrue();
    assertThat(CoverageZone.isCovered("San José de Carrasco")).isTrue();
  }

  /**
   * El bug concreto que destapó todo esto: Montes de Solymar existía para el
   * agente pero no para {@code LeadService}, que la marcaba fuera de cobertura.
   */
  @Test
  void montesDeSolymarEstaCubierta() {
    assertThat(CoverageZone.isCovered("Montes de Solymar")).isTrue();
    assertThat(CoverageZone.isCovered("montes de solymar")).isTrue();
  }

  /** Toda etiqueta canónica tiene que reconocerse a sí misma; si no, el catálogo miente. */
  @Test
  void todaEtiquetaCanonicaSeReconoceASiMisma() {
    for (CoverageZone zone : CoverageZone.values()) {
      assertThat(CoverageZone.isCovered(zone.label()))
          .as("zona %s", zone.label())
          .isTrue();
      assertThat(CoverageZone.fromLabel(zone.label())).contains(zone);
      assertThat(CoverageZone.covers(zone.label(), zone.label())).isTrue();
    }
    assertThat(CoverageZone.LABELS).hasSize(CoverageZone.values().length);
  }
}
