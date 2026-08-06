package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.LeadPhotoRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderLeadDeclineRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * El proveedor en pausa ({@code acceptingWork=false}) no puede recibir
 * trabajo nuevo por NINGUNA cara del producto.
 *
 * <p>Nació de un caso real medido en prod el 2026-08-05: a Melissa
 * (proveedora 10, pastelería) el matching le asignó 6 pedidos entre el 15/07
 * y el 31/07 —#108, #109, #115, #133, #142, #146— y a cada cliente se le dijo
 * "estoy contactando a Melissa", mientras su panel le mostraba CERO
 * oportunidades porque tenía {@code accepting_work=false}. Los 6 terminaron
 * CANCELLED. La bandeja respetaba la pausa; el matching, el preview público y
 * el push de re-oferta no. Esa divergencia es la que estos tests clavan.
 */
@ExtendWith(MockitoExtension.class)
class ProviderPauseMatchingConsistencyTest {

  @Mock private ProviderRepository providerRepository;
  @Mock private LeadPaymentRepository leadPaymentRepository;
  @Mock private ProviderLeadDeclineRepository declineRepository;
  @Mock private LeadRepository leadRepository;
  @Mock private LeadPhotoRepository photoRepository;
  @Mock private LeadAssignmentService leadAssignmentService;
  @Mock private LeadTimelineService timelineService;

  private ProviderCatalogService catalogService;
  private ProviderOpportunityService opportunityService;

  @BeforeEach
  void setUp() {
    catalogService = new ProviderCatalogService(providerRepository, leadPaymentRepository, declineRepository);
    opportunityService = new ProviderOpportunityService(
        leadRepository, declineRepository, photoRepository, catalogService,
        leadAssignmentService, timelineService);
    // Sin deudas: aísla la pausa como única causa de exclusión.
    lenient().when(leadPaymentRepository.findProviderIdsByCommissionStatus(CommissionStatus.OVERDUE))
        .thenReturn(Set.of());
    lenient().when(leadPaymentRepository.findByProviderIdOrderByCreatedAtDesc(10L))
        .thenReturn(List.of());
  }

  /** Melissa tal como estaba en prod, salvo por el flag que se varía. */
  private Provider melissa(Boolean acceptingWork) {
    Provider p = new Provider();
    p.setId(10L);
    p.setName("Melissa");
    p.setPhone("094863473");
    p.setStatus(ProviderStatus.AVAILABLE);
    p.setCategories("pasteleria");
    p.setPrimaryZone("Shangrila");
    p.setCity("Ciudad de la Costa");
    p.setAcceptingWork(acceptingWork);
    return p;
  }

  private Lead pedidoDePasteleria() {
    Lead lead = new Lead();
    lead.setId(146L);
    lead.setDetectedCategory("pasteleria");
    lead.setLocation("Ciudad de la Costa");
    lead.setStatus(LeadStatus.NEW);
    lead.setReadyForMatching(true);
    lead.setProblem("Pedido de pastelería");
    return lead;
  }

  @Test
  void elMatchingNoLeAsignaPedidosAlProveedorEnPausa() {
    when(providerRepository.findAll()).thenReturn(List.of(melissa(false)));

    assertThat(catalogService.findMatches("pasteleria", "Ciudad de la Costa")).isEmpty();
  }

  @Test
  void elPreviewPublicoTampocoLoPrometeSiEstaEnPausa() {
    when(providerRepository.findAll()).thenReturn(List.of(melissa(false)));

    assertThat(catalogService.publicPreview("pasteleria", "Ciudad de la Costa", 5).count()).isZero();
  }

  /**
   * El invariante que se rompió con Melissa: lo que el matching promete tiene
   * que ser exactamente lo que la bandeja del proveedor muestra.
   */
  @Test
  void laBandejaYElMatchingContestanLoMismoSobreElProveedorEnPausa() {
    Provider enPausa = melissa(false);
    when(providerRepository.findAll()).thenReturn(List.of(enPausa));

    boolean elMatchingLoOfrece = !catalogService.findMatches("pasteleria", "Ciudad de la Costa").isEmpty();
    boolean laBandejaSeLoMuestra = !opportunityService.listFor(enPausa).isEmpty();

    assertThat(elMatchingLoOfrece).isEqualTo(laBandejaSeLoMuestra);
    assertThat(elMatchingLoOfrece).isFalse();
  }

  /** La bandeja vacía no puede convivir con un POST de aceptar que funciona. */
  @Test
  void elProveedorEnPausaTampocoPuedeAceptarPorPostDirecto() {
    Provider enPausa = melissa(false);

    assertThatThrownBy(() -> opportunityService.accept(enPausa, 146L))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("no estás recibiendo trabajos nuevos");
    verify(leadAssignmentService, never()).acceptForProvider(org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  // --- Controles negativos: la pausa filtra la pausa, no la categoría ---

  @Test
  void sinPausaElProveedorSigueRecibiendoMatchingYVeSuBandeja() {
    Provider disponible = melissa(true);
    when(providerRepository.findAll()).thenReturn(List.of(disponible));
    when(leadRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(pedidoDePasteleria()));
    when(declineRepository.findByProviderId(10L)).thenReturn(List.of());
    when(photoRepository.countByLeadId(146L)).thenReturn(0);

    assertThat(catalogService.findMatches("pasteleria", "Ciudad de la Costa")).hasSize(1);
    assertThat(opportunityService.listFor(disponible)).hasSize(1);
  }

  /**
   * {@code acceptingWork} es null en las fichas anteriores a la columna: el
   * default histórico es "disponible", no "en pausa". Tratarlo como pausa
   * habría apagado el matching de todo el padrón viejo de una.
   */
  @Test
  void acceptingWorkNullEsDisponibleNoPausado() {
    when(providerRepository.findAll()).thenReturn(List.of(melissa(null)));

    assertThat(catalogService.findMatches("pasteleria", "Ciudad de la Costa")).hasSize(1);
  }
}
