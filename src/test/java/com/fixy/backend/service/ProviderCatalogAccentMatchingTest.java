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
 * El matching de zona debe ignorar acentos: la Melissa real de prod está
 * registrada con primaryZone "Shangrila" (sin tilde) y un cliente que
 * escribe "Shangrilá" (con tilde, como está en el catálogo de zonas MVP)
 * no matcheaba — descubierto en la prueba de push del 15/07. Mismo tipo
 * de bug que el de categorías por etiqueta humana.
 */
@SpringBootTest
@Transactional
class ProviderCatalogAccentMatchingTest {

  @Autowired private ProviderCatalogService providerCatalogService;
  @Autowired private ProviderRepository providerRepository;

  private Provider persistProvider(String zone) {
    Provider provider = new Provider();
    provider.setName("Melissa Acentos Test");
    provider.setPhone("099555666");
    provider.setCategories("pasteleria");
    provider.setPrimaryZone(zone);
    provider.setStatus(ProviderStatus.AVAILABLE);
    return providerRepository.save(provider);
  }

  @Test
  void zoneWithAccentMatchesProviderRegisteredWithoutAccent() {
    persistProvider("Shangrila");

    List<ProviderCatalogItem> matches = providerCatalogService.findMatches("pasteleria", "Shangrilá");

    assertThat(matches).extracting(ProviderCatalogItem::name).contains("Melissa Acentos Test");
  }

  @Test
  void zoneWithoutAccentMatchesProviderRegisteredWithAccent() {
    persistProvider("Shangrilá");

    List<ProviderCatalogItem> matches = providerCatalogService.findMatches("pasteleria", "shangrila");

    assertThat(matches).extracting(ProviderCatalogItem::name).contains("Melissa Acentos Test");
  }
}
