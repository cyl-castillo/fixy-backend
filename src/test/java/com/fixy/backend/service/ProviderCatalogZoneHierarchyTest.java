package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.ProviderRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * El matching de zona tiene que entender que "Ciudad de la Costa" contiene a
 * Solymar, Lagomar y compañía, en vez de compararlas como strings sueltos.
 *
 * <p>Los dos casos de acá son proveedores REALES de prod (relevados el
 * 2026-08-03), no inventados:
 *
 * <ul>
 *   <li><b>Daya Dream Deco</b> — {@code primaryZone="Solymar"},
 *   {@code coverageZones=[]}, {@code city="Ciudad de la Costa"}. Declara la
 *   ciudad entera y hoy es invisible para un pedido de decoración en Lagomar.</li>
 *   <li><b>Carnot Clima</b> — {@code primaryZone="Ciudad de la Costa"},
 *   {@code coverageZones=["Solymar"]}. Mismo problema en El Pinar.</li>
 * </ul>
 *
 * <p>Categorías y zonas propias por test donde se puede, y aserciones filtradas
 * por el nombre del proveedor sembrado, para no chocar con el catálogo
 * compartido de H2 (ver memoria fixy-backend-test-interferencia-contextos).
 */
@SpringBootTest
@Transactional
class ProviderCatalogZoneHierarchyTest {

  @Autowired private ProviderCatalogService providerCatalogService;
  @Autowired private ProviderRepository providerRepository;

  private Provider persist(String name, String category, String primaryZone,
      String coverageZones, String city) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone("099000111");
    provider.setCategories(category);
    provider.setPrimaryZone(primaryZone);
    provider.setCoverageZones(coverageZones);
    provider.setCity(city);
    provider.setStatus(ProviderStatus.AVAILABLE);
    return providerRepository.save(provider);
  }

  private List<String> matchNames(String category, String location) {
    return providerCatalogService.findMatches(category, location).stream()
        .map(ProviderCatalogItem::name)
        .toList();
  }

  /**
   * Caso Carnot Clima: declara {@code primaryZone = "Ciudad de la Costa"}, o
   * sea que dice cubrir la ciudad entera, y hoy no aparece en ningún barrio.
   */
  @Test
  void proveedorConPrimaryZoneDeCiudadApareceEnUnBarrio() {
    persist("Carnot Zonas Test", "aires_acondicionados", "Ciudad de la Costa", "Solymar", null);

    assertThat(matchNames("aires_acondicionados", "El Pinar")).contains("Carnot Zonas Test");
    assertThat(matchNames("aires_acondicionados", "Shangrilá")).contains("Carnot Zonas Test");
    assertThat(matchNames("aires_acondicionados", "Montes de Solymar")).contains("Carnot Zonas Test");
  }

  /** Caso Nueva Era / Melissa: el paraguas declarado dentro de {@code coverageZones}. */
  @Test
  void elParaguasDeclaradoEnCoverageZonesTambienCubreCadaBarrio() {
    persist("Nueva Era Zonas Test", "barometrica", "Shangrilá", "Ciudad de la Costa", null);

    assertThat(matchNames("barometrica", "Lagomar")).contains("Nueva Era Zonas Test");
  }

  /**
   * Caso Daya Dream Deco: {@code primaryZone="Solymar"} y
   * {@code city="Ciudad de la Costa"}. La ciudad es su DIRECCIÓN, no una
   * declaración de cobertura — expandirla la haría recibir pedidos de barrios
   * que nunca dijo cubrir. Solo {@code primaryZone} y {@code coverageZones}
   * entran en la jerarquía.
   */
  @Test
  void laCiudadDelDomicilioNoAmpliaLaCobertura() {
    persist("Daya Zonas Test", "decoracion_fiestas", "Solymar", null, "Ciudad de la Costa");

    assertThat(matchNames("decoracion_fiestas", "Lagomar")).doesNotContain("Daya Zonas Test");
    assertThat(matchNames("decoracion_fiestas", "Solymar")).contains("Daya Zonas Test");
  }

  /**
   * El caso más frecuente del embudo: 35 de 135 pedidos reales (26%) llegan con
   * la zona genérica "Ciudad de la Costa" porque el cliente contesta con la
   * ciudad y no con el barrio.
   */
  @Test
  void pedidoConZonaGenericaAlcanzaAlProveedorRegistradoPorBarrio() {
    persist("Plomero Solo Barrio Test", "plomeria", "Lomas de Solymar", null, null);

    assertThat(matchNames("plomeria", "Ciudad de la Costa")).contains("Plomero Solo Barrio Test");
  }

  /** La jerarquía no puede degenerar en "todos matchean con todos". */
  @Test
  void proveedorDeUnBarrioNoApareceEnOtroBarrio() {
    persist("Plomero Solo Barrio Test", "plomeria", "Lomas de Solymar", null, null);

    assertThat(matchNames("plomeria", "El Pinar")).doesNotContain("Plomero Solo Barrio Test");
    assertThat(matchNames("plomeria", "Pocitos")).doesNotContain("Plomero Solo Barrio Test");
  }

  /**
   * A igualdad de reputación, el que nombró el barrio va antes que el que
   * cubre la ciudad entera: el vecino es mejor match que el generalista.
   * Importa porque {@code contactTopMatch} contacta al PRIMERO de la lista.
   */
  @Test
  void elProveedorDelBarrioVaAntesQueElQueCubreLaCiudad() {
    persist("Generalista Ciudad Test", "jardineria", "Ciudad de la Costa", null, null);
    persist("Vecino Aeroparque Test", "jardineria", "Aeroparque", null, null);

    List<String> matches = matchNames("jardineria", "Aeroparque");

    assertThat(matches).contains("Vecino Aeroparque Test", "Generalista Ciudad Test");
    assertThat(matches.indexOf("Vecino Aeroparque Test"))
        .isLessThan(matches.indexOf("Generalista Ciudad Test"));
  }

  /** Un proveedor sin ninguna cobertura declarada sigue sin matchear una zona concreta. */
  @Test
  void proveedorSinZonasNoMatcheaUnaZonaConcreta() {
    persist("Sin Cobertura Test", "plomeria", null, null, null);

    assertThat(matchNames("plomeria", "Solymar")).doesNotContain("Sin Cobertura Test");
  }
}
