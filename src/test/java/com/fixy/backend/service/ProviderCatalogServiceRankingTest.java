package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.ProviderRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * H_A: findMatches ordena por reputación (reorder-only, sin exclusividad ni
 * ventaja temporal) y trata a los proveedores sin calificaciones con un
 * prior neutro para que no queden enterrados al fondo (cold-start).
 */
@ExtendWith(MockitoExtension.class)
class ProviderCatalogServiceRankingTest {

  @Mock
  private ProviderRepository providerRepository;

  private ProviderCatalogService service;

  private Provider provider(Long id, String name, Double ratingAverage, Integer ratingCount) {
    Provider p = new Provider();
    p.setId(id);
    p.setName(name);
    p.setPhone("09900000" + id);
    p.setStatus(ProviderStatus.AVAILABLE);
    p.setCategories("plomeria");
    p.setPrimaryZone("Pocitos");
    p.setRatingAverage(ratingAverage);
    p.setRatingCount(ratingCount);
    return p;
  }

  @Test
  void ordenaPorMejorReputacionPrimero() {
    service = new ProviderCatalogService(providerRepository);
    Provider bueno = provider(1L, "Bueno", 4.8, 20);
    Provider regular = provider(2L, "Regular", 3.2, 15);
    Provider malo = provider(3L, "Malo", 2.0, 10);

    when(providerRepository.findAll()).thenReturn(List.of(malo, bueno, regular));

    List<ProviderCatalogItem> matches = service.findMatches("plomeria", "Pocitos");

    assertThat(matches).extracting(ProviderCatalogItem::id)
        .containsExactly(1L, 2L, 3L);
  }

  @Test
  void proveedorNuevoSinCalificacionesNoQuedaAlFondo() {
    service = new ProviderCatalogService(providerRepository);
    // Nuevo con prior ~4.0 debe intercalarse entre el excelente (4.8) y el
    // mediocre (3.0), NUNCA último por el solo hecho de no tener rating.
    Provider excelente = provider(1L, "Excelente", 4.8, 30);
    Provider nuevo = provider(2L, "Nuevo", null, 0);
    Provider mediocre = provider(3L, "Mediocre", 3.0, 12);

    when(providerRepository.findAll()).thenReturn(List.of(mediocre, nuevo, excelente));

    List<ProviderCatalogItem> matches = service.findMatches("plomeria", "Pocitos");

    assertThat(matches).extracting(ProviderCatalogItem::id)
        .containsExactly(1L, 2L, 3L);
  }

  @Test
  void empatadosVariosProveedoresNuevosNoRompenElMatching() {
    service = new ProviderCatalogService(providerRepository);
    Provider nuevo1 = provider(1L, "Nuevo1", null, 0);
    Provider nuevo2 = provider(2L, "Nuevo2", null, 0);

    when(providerRepository.findAll()).thenReturn(List.of(nuevo1, nuevo2));

    List<ProviderCatalogItem> matches = service.findMatches("plomeria", "Pocitos");

    assertThat(matches).hasSize(2);
    assertThat(matches).extracting(ProviderCatalogItem::id)
        .containsExactlyInAnyOrder(1L, 2L);
  }
}
