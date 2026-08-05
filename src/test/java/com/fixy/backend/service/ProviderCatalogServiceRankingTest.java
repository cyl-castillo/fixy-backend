package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.ProviderLeadDeclineRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
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

  @Mock
  private LeadPaymentRepository leadPaymentRepository;

  @Mock
  private ProviderLeadDeclineRepository declineRepository;

  private ProviderCatalogService service;

  @BeforeEach
  void noOverdueProvidersByDefault() {
    lenient().when(leadPaymentRepository.findProviderIdsByCommissionStatus(CommissionStatus.OVERDUE))
        .thenReturn(Set.of());
  }

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
    service = new ProviderCatalogService(providerRepository, leadPaymentRepository, declineRepository);
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
    service = new ProviderCatalogService(providerRepository, leadPaymentRepository, declineRepository);
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
    service = new ProviderCatalogService(providerRepository, leadPaymentRepository, declineRepository);
    Provider nuevo1 = provider(1L, "Nuevo1", null, 0);
    Provider nuevo2 = provider(2L, "Nuevo2", null, 0);

    when(providerRepository.findAll()).thenReturn(List.of(nuevo1, nuevo2));

    List<ProviderCatalogItem> matches = service.findMatches("plomeria", "Pocitos");

    assertThat(matches).hasSize(2);
    assertThat(matches).extracting(ProviderCatalogItem::id)
        .containsExactlyInAnyOrder(1L, 2L);
  }

  /**
   * Honestidad en reputación: el prior 4.0 de {@code rankingScore} es solo
   * para ordenar el matching internamente. El preview público cliente-facing
   * nunca debe mostrar un promedio a un proveedor con ratingCount == 0 — el
   * front decide "Nuevo en Fixy" en base a ratingCount, así que el DTO debe
   * exponerlo.
   */
  @Test
  void previewPublicoNoExponeRatingInfladoParaProveedorSinCalificaciones() {
    service = new ProviderCatalogService(providerRepository, leadPaymentRepository, declineRepository);
    Provider nuevo = provider(1L, "Nuevo", null, 0);

    when(providerRepository.findAll()).thenReturn(List.of(nuevo));

    var preview = service.publicPreview("plomeria", "Pocitos", 3);

    assertThat(preview.count()).isEqualTo(1);
    assertThat(preview.sample()).hasSize(1);
    var item = preview.sample().get(0);
    assertThat(item.ratingAverage()).isNull();
    assertThat(item.ratingCount()).isZero();
  }

  @Test
  void previewPublicoExponeElRatingRealParaProveedorConCalificaciones() {
    service = new ProviderCatalogService(providerRepository, leadPaymentRepository, declineRepository);
    Provider calificado = provider(1L, "Calificado", 4.8, 12);

    when(providerRepository.findAll()).thenReturn(List.of(calificado));

    var preview = service.publicPreview("plomeria", "Pocitos", 3);

    var item = preview.sample().get(0);
    assertThat(item.ratingAverage()).isEqualTo(4.8);
    assertThat(item.ratingCount()).isEqualTo(12);
  }

  /**
   * Caso real de la guardia del 2026-08-04, medido en prod: el preview
   * público devolvía {@code count=1} con Barométrica Nueva Era para
   * (barometrica, Montes de Solymar), y el mismo pedido por el chat en el
   * mismo minuto (lead #204) contestaba "Por ahora no tengo proveedores
   * libres en Montes de Solymar para barometrica" — Nueva Era estaba OVERDUE
   * desde el 30/07 por la comisión #3 y solo {@code findMatches} la filtraba.
   * El preview le prometía al cliente un proveedor que el matching nunca le
   * iba a asignar.
   */
  @Test
  void previewPublicoNoPrometeAlProveedorConComisionVencidaQueElMatchingYaExcluye() {
    Provider nuevaEra = provider(12L, "Barometrica Nueva Era", 5.0, 1);
    nuevaEra.setCategories("barometrica");
    nuevaEra.setPrimaryZone("Montes de Solymar");

    when(providerRepository.findAll()).thenReturn(List.of(nuevaEra));
    when(leadPaymentRepository.findProviderIdsByCommissionStatus(CommissionStatus.OVERDUE))
        .thenReturn(Set.of(12L));

    service = new ProviderCatalogService(providerRepository, leadPaymentRepository, declineRepository);

    var preview = service.publicPreview("barometrica", "Montes de Solymar", 3);

    assertThat(preview.count()).isZero();
    assertThat(preview.sample()).isEmpty();
    // La otra cara del producto ya decía esto mismo; ahora coinciden.
    assertThat(service.findMatches("barometrica", "Montes de Solymar")).isEmpty();
  }

  /**
   * Control negativo: sin comisión vencida el preview sigue mostrando al
   * proveedor. El fix filtra la deuda, no la categoría.
   */
  @Test
  void previewPublicoSigueMostrandoAlProveedorSinComisionVencida() {
    Provider nuevaEra = provider(12L, "Barometrica Nueva Era", 5.0, 1);
    nuevaEra.setCategories("barometrica");
    nuevaEra.setPrimaryZone("Montes de Solymar");

    when(providerRepository.findAll()).thenReturn(List.of(nuevaEra));

    service = new ProviderCatalogService(providerRepository, leadPaymentRepository, declineRepository);

    var preview = service.publicPreview("barometrica", "Montes de Solymar", 3);

    assertThat(preview.count()).isEqualTo(1);
    assertThat(preview.sample()).hasSize(1);
  }
}
