package com.fixy.backend.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Lead real #276 (2026-10-09): "Necesito cambiar la garrafa, lomas de solymar" quedó una hora sin
 * categoría porque "garrafa" no era keyword de nada; el LLM extraía {@code mandados} y el guard
 * determinista lo descartaba (el vecino no había dicho "mandado"). El cambio de garrafa tiene
 * categoría propia ({@code garrafa}, con una distribuidora de proveedor, que NO debe recibir
 * mandados): el vocabulario tiene que detectarlo sin depender del LLM.
 */
class GarrafaDetectionTest {

  @Test
  void cambiarLaGarrafaEsSuPropiaCategoria() {
    DomainCatalog catalog = DomainCatalog.get();
    assertThat(catalog.detectCategory("Necesito cambiar la garrafa, lomas de solymar").map(CategoryDef::id))
        .contains("garrafa");
    assertThat(catalog.detectCategory("se me terminó el supergas").map(CategoryDef::id)).contains("garrafa");
    assertThat(catalog.detectCategory("cambio de garrafa para hoy").map(CategoryDef::id)).contains("garrafa");
  }

  @Test
  void laRecargaDeGasDelSplitSigueSiendoAire() {
    assertThat(DomainCatalog.get().detectCategory("el split no enfría, necesita recarga de gas").map(CategoryDef::id))
        .contains("aires_acondicionados");
  }
}
