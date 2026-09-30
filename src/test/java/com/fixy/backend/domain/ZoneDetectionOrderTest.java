package com.fixy.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Core Fase 2: la detección de zona en texto libre mantiene la MISMA prioridad de match que la
 * tabla ZONE_TOKENS de AgentService (caso real lead #132: "montes" cayó a "Ciudad de la Costa"
 * porque "solymar" a secas matcheaba antes que las zonas específicas). Ahora el orden sale de
 * {@code detect.priority} del YAML y este test lo fija.
 */
class ZoneDetectionOrderTest {

  private final DomainCatalog catalog = DomainCatalog.get();

  /** Orden de evaluación de la tabla vieja: (token, zona). */
  private static final List<String[]> LEGACY_ORDER = List.of(
      new String[] {"montes de solymar", "MONTES_DE_SOLYMAR"},
      new String[] {"montes", "MONTES_DE_SOLYMAR"},
      new String[] {"lomas de solymar", "LOMAS_DE_SOLYMAR"},
      new String[] {"lomas", "LOMAS_DE_SOLYMAR"},
      new String[] {"colinas de solymar", "COLINAS_DE_SOLYMAR"},
      new String[] {"colinas", "COLINAS_DE_SOLYMAR"},
      new String[] {"shangrila", "SHANGRILA"},
      new String[] {"el pinar", "EL_PINAR"},
      new String[] {"pinar", "EL_PINAR"},
      new String[] {"barra de carrasco", "BARRA_DE_CARRASCO"},
      new String[] {"parque miramar", "PARQUE_MIRAMAR"},
      new String[] {"miramar", "PARQUE_MIRAMAR"},
      new String[] {"san jose de carrasco", "SAN_JOSE_DE_CARRASCO"},
      new String[] {"aeroparque", "AEROPARQUE"},
      new String[] {"lagomar", "LAGOMAR"},
      new String[] {"solymar", "SOLYMAR"});

  @Test
  void elOrdenDeEvaluacionEsElDeLaTablaVieja() {
    List<String[]> actual = new ArrayList<>();
    // Se reconstruye el orden efectivo probando cada token contra el catálogo: el token de cada
    // zona detecta a esa zona, y las específicas que contienen "solymar" no caen en SOLYMAR.
    for (String[] entry : LEGACY_ORDER) {
      String zoneId = catalog.detectZone(entry[0]).map(ZoneDef::id).orElse(null);
      actual.add(new String[] {entry[0], zoneId});
    }
    assertThat(actual).usingRecursiveComparison().isEqualTo(LEGACY_ORDER);
  }

  @Test
  void lasEspecificasQueContienenSolymarGananASolymarASecas() {
    assertThat(catalog.detectZone("vivo en montes de solymar")).map(ZoneDef::id).contains("MONTES_DE_SOLYMAR");
    assertThat(catalog.detectZone("lomas de solymar")).map(ZoneDef::id).contains("LOMAS_DE_SOLYMAR");
    assertThat(catalog.detectZone("colinas de solymar")).map(ZoneDef::id).contains("COLINAS_DE_SOLYMAR");
    assertThat(catalog.detectZone("solymar")).map(ZoneDef::id).contains("SOLYMAR");
    assertThat(catalog.detectZone("solymar y lomas")).map(ZoneDef::id).contains("LOMAS_DE_SOLYMAR");
  }

  @Test
  void elParaguasSeEvaluaSiempreAlFinal() {
    assertThat(catalog.detectZone("ciudad de la costa")).map(ZoneDef::id).contains("CIUDAD_DE_LA_COSTA");
    assertThat(catalog.detectZone("canelones")).map(ZoneDef::id).contains("CIUDAD_DE_LA_COSTA");
    // Un barrio mencionado junto al paraguas gana: el paraguas es el último recurso.
    assertThat(catalog.detectZone("ciudad de la costa, en lagomar")).map(ZoneDef::id).contains("LAGOMAR");
    // "la costa" es alias de cobertura pero NO token de detección (igual que antes).
    assertThat(catalog.detectZone("por la costa")).isEmpty();
    assertThat(catalog.detectZone("pocitos")).isEmpty();
  }

  @Test
  void ignoraTildesYMayusculas() {
    assertThat(catalog.detectZone("Estoy en SHANGRILÁ")).map(ZoneDef::id).contains("SHANGRILA");
    assertThat(catalog.detectZone("San José de Carrasco")).map(ZoneDef::id).contains("SAN_JOSE_DE_CARRASCO");
  }
}
