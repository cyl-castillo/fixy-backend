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
 * Aprobar es lo que enciende el matching: el proveedor {@code NEW} (ficha
 * cargada, todavía sin el visto bueno de un humano) no recibe trabajo por
 * ninguna cara del producto.
 *
 * <p>Es la otra mitad del caso Melissa (proveedora 10, pastelería), medido en
 * prod: estuvo {@code NEW} desde su alta hasta que Carlos la aprobó el
 * 2026-08-05, y en esas tres semanas el matching le asignó 6 pedidos —#108,
 * #109, #115, #133, #142, #146— diciéndole a cada cliente "estoy contactando a
 * Melissa". Los 6 terminaron CANCELLED. La mitad de la pausa
 * ({@code accepting_work=false}) la cerró
 * {@link ProviderPauseMatchingConsistencyTest} el 05/08; esta cierra la del
 * estado del padrón. El autoregistro nunca estuvo expuesto —nace
 * {@code INACTIVE}—; el alta manual por la API de ops sí, y es justo el camino
 * por el que entran los proveedores de la captación.
 */
@ExtendWith(MockitoExtension.class)
class ProviderApprovalGatesMatchingTest {

  @Mock private ProviderRepository providerRepository;
  @Mock private LeadPaymentRepository leadPaymentRepository;
  @Mock private ProviderLeadDeclineRepository declineRepository;
  @Mock private LeadRepository leadRepository;
  @Mock private LeadPhotoRepository photoRepository;
  @Mock private LeadAssignmentService leadAssignmentService;
  @Mock private LeadTimelineService timelineService;
  @Mock private com.fixy.backend.repository.LeadRatingRepository leadRatingRepository;

  private ProviderCatalogService catalogService;
  private ProviderOpportunityService opportunityService;

  @BeforeEach
  void setUp() {
    catalogService = new ProviderCatalogService(providerRepository, leadPaymentRepository, declineRepository, leadRatingRepository);
    opportunityService = new ProviderOpportunityService(
        leadRepository, declineRepository, photoRepository, catalogService,
        leadAssignmentService, timelineService);
    // Sin deudas y sin pausa: aísla el estado del padrón como única causa.
    lenient().when(leadPaymentRepository.findProviderIdsByCommissionStatus(CommissionStatus.OVERDUE))
        .thenReturn(Set.of());
    lenient().when(leadPaymentRepository.findByProviderIdOrderByCreatedAtDesc(10L))
        .thenReturn(List.of());
  }

  /** Melissa tal como estaba en prod, salvo por el estado que se varía. */
  private Provider melissa(ProviderStatus status) {
    Provider p = new Provider();
    p.setId(10L);
    p.setName("Melissa");
    p.setPhone("094863473");
    p.setStatus(status);
    p.setCategories("pasteleria");
    p.setPrimaryZone("Shangrila");
    p.setCity("Ciudad de la Costa");
    p.setAcceptingWork(true);
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
  void elMatchingNoLeAsignaPedidosAlProveedorSinAprobar() {
    when(providerRepository.findAll()).thenReturn(List.of(melissa(ProviderStatus.NEW)));

    assertThat(catalogService.findMatches("pasteleria", "Ciudad de la Costa")).isEmpty();
  }

  @Test
  void elPreviewPublicoTampocoLoPrometeSinAprobar() {
    when(providerRepository.findAll()).thenReturn(List.of(melissa(ProviderStatus.NEW)));

    assertThat(catalogService.publicPreview("pasteleria", "Ciudad de la Costa", 5).count()).isZero();
  }

  /** Las dos caras del producto tienen que contestar lo mismo sobre él. */
  @Test
  void laBandejaYElMatchingContestanLoMismoSobreElNoAprobado() {
    Provider sinAprobar = melissa(ProviderStatus.NEW);
    when(providerRepository.findAll()).thenReturn(List.of(sinAprobar));

    boolean elMatchingLoOfrece = !catalogService.findMatches("pasteleria", "Ciudad de la Costa").isEmpty();
    boolean laBandejaSeLoMuestra = !opportunityService.listFor(sinAprobar).isEmpty();

    assertThat(elMatchingLoOfrece).isEqualTo(laBandejaSeLoMuestra);
    assertThat(elMatchingLoOfrece).isFalse();
  }

  /** Bandeja vacía y POST directo de aceptar tienen que decir lo mismo. */
  @Test
  void elNoAprobadoTampocoPuedeAceptarPorPostDirecto() {
    Provider sinAprobar = melissa(ProviderStatus.NEW);

    assertThatThrownBy(() -> opportunityService.accept(sinAprobar, 146L))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("tu cuenta no está activa todavía");
    verify(leadAssignmentService, never()).acceptForProvider(org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  // --- Controles negativos: el gate filtra la aprobación, no la categoría ---

  /**
   * El caso Melissa completo: el mismo proveedor, el mismo pedido, y lo único
   * que cambia es el visto bueno de Carlos en el admin.
   */
  @Test
  void aprobarloEsLoQueEnciendeElMatchingYSuBandeja() {
    Provider aprobada = melissa(ProviderStatus.AVAILABLE);
    when(providerRepository.findAll()).thenReturn(List.of(aprobada));
    when(leadRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(pedidoDePasteleria()));
    when(declineRepository.findByProviderId(10L)).thenReturn(List.of());
    when(photoRepository.countByLeadId(146L)).thenReturn(0);

    assertThat(catalogService.findMatches("pasteleria", "Ciudad de la Costa")).hasSize(1);
    assertThat(opportunityService.listFor(aprobada)).hasSize(1);
  }

  /**
   * Los estados de prospección y de baja del padrón quedan como estaban a
   * propósito (nada en el código los escribe hoy, solo el PATCH de ops): este
   * fix agrega NEW y nada más. Si algún día se decide que RESPONDED tampoco
   * trabaja, es una decisión de Carlos y este test es el que hay que cambiar.
   */
  @Test
  void losEstadosDeProspeccionSiguenRecibiendoTrabajoComoAntes() {
    Provider respondio = melissa(ProviderStatus.RESPONDED);
    when(providerRepository.findAll()).thenReturn(List.of(respondio));

    assertThat(catalogService.findMatches("pasteleria", "Ciudad de la Costa")).hasSize(1);
  }
}
