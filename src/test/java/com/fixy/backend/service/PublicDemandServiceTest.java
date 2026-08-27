package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.fixy.backend.dto.PublicDemandResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit puro (sin contexto Spring) del conteo de demanda abierta que alimenta
 * el hero de {@code /sumate}. Es el número que se le muestra a un vecino que
 * está decidiendo si se da de alta: cada filtro de acá existe para que no le
 * mientan (smoke, pedidos ya tomados, demanda vieja, rubros donde no se puede
 * dar de alta).
 */
@ExtendWith(MockitoExtension.class)
class PublicDemandServiceTest {

  private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC);
  private static final OffsetDateTime NOW = OffsetDateTime.now(FIXED);

  @Mock
  private LeadRepository leadRepository;

  private PublicDemandService service;
  private final List<Lead> leads = new ArrayList<>();

  @BeforeEach
  void setUp() {
    service = new PublicDemandService(leadRepository, FIXED);
    leads.clear();
    // El servicio recorre un status por vez; cada lead responde solo a la
    // llamada de SU status, igual que el repositorio real.
    for (LeadStatus status : LeadStatus.values()) {
      lenient().when(leadRepository.findByStatusOrderByCreatedAtDesc(status))
          .thenReturn(leads.stream().filter(l -> l.getStatus() == status).toList());
    }
  }

  private Lead lead(LeadStatus status, String category, String problem, OffsetDateTime createdAt) {
    Lead lead = new Lead();
    lead.setStatus(status);
    lead.setDetectedCategory(category);
    lead.setProblem(problem);
    // createdAt lo fija @PrePersist y no tiene setter; acá no se persiste
    // nada, así que se inyecta directo (mismo truco que envejecer por SQL
    // en los tests de scheduler, sin necesidad de contexto Spring).
    ReflectionTestUtils.setField(lead, "createdAt", createdAt);
    return lead;
  }

  /** Recarga los stubs después de llenar {@link #leads} (los mocks capturan la lista ya filtrada). */
  private void given(Lead... newLeads) {
    leads.addAll(Arrays.asList(newLeads));
    for (LeadStatus status : LeadStatus.values()) {
      lenient().when(leadRepository.findByStatusOrderByCreatedAtDesc(status))
          .thenReturn(leads.stream().filter(l -> l.getStatus() == status).toList());
    }
  }

  @Test
  void agrupaPorOficioYOrdenaDeMayorAMenor() {
    given(
        lead(LeadStatus.NEW, "mandados", "necesito que me hagan un mandado", NOW.minusDays(1)),
        lead(LeadStatus.NEW, "mandados", "traer una garrafa", NOW.minusDays(2)),
        lead(LeadStatus.PROVIDER_CONTACTED, "mandados", "compras del super", NOW.minusDays(3)),
        lead(LeadStatus.NEW, "pasteleria", "torta de cumpleaños", NOW.minusDays(1)),
        lead(LeadStatus.IN_REVIEW, "barometrica", "pozo lleno", NOW.minusDays(4))
    );

    PublicDemandResponse response = service.get();

    assertThat(response.totalOpen()).isEqualTo(5);
    assertThat(response.categories()).extracting(PublicDemandResponse.Item::category)
        .containsExactly("mandados", "barometrica", "pasteleria");
    assertThat(response.categories().getFirst().openCount()).isEqualTo(3);
    assertThat(response.categories().getFirst().label()).isEqualTo("mandados y trámites");
  }

  @Test
  void empateSeDesempataPorIdParaQueElOrdenSeaEstable() {
    given(
        lead(LeadStatus.NEW, "pasteleria", "torta", NOW.minusDays(1)),
        lead(LeadStatus.NEW, "mandados", "mandado", NOW.minusDays(1))
    );

    assertThat(service.get().categories()).extracting(PublicDemandResponse.Item::category)
        .containsExactly("mandados", "pasteleria");
  }

  @Test
  void noCuentaPedidosQueYaTomoUnProveedor() {
    given(
        lead(LeadStatus.ASSIGNED, "mandados", "ya lo tomaron", NOW.minusDays(1)),
        lead(LeadStatus.IN_PROGRESS, "mandados", "en curso", NOW.minusDays(1)),
        lead(LeadStatus.COMPLETED, "mandados", "terminado", NOW.minusDays(1))
    );

    assertThat(service.get().totalOpen()).isZero();
    assertThat(service.get().categories()).isEmpty();
  }

  @Test
  void noCuentaTraficoSmoke() {
    given(
        lead(LeadStatus.NEW, "mandados", "[smoke] pedido sintético", NOW.minusDays(1)),
        lead(LeadStatus.NEW, "mandados", "smoke necesito un mandadero", NOW.minusDays(1)),
        lead(LeadStatus.NEW, "mandados", "necesito un mandadero de verdad", NOW.minusDays(1))
    );

    assertThat(service.get().totalOpen()).isEqualTo(1);
  }

  @Test
  void noCuentaDemandaMasViejaQueLaVentana() {
    given(
        lead(LeadStatus.NEW, "barometrica", "pozo de hace mucho",
            NOW.minusDays(PublicDemandService.FRESHNESS_DAYS + 1)),
        lead(LeadStatus.NEW, "barometrica", "pozo reciente",
            NOW.minusDays(PublicDemandService.FRESHNESS_DAYS - 1))
    );

    assertThat(service.get().totalOpen()).isEqualTo(1);
  }

  @Test
  void ignoraOficiosQueNoSePuedenDarDeAlta() {
    given(
        lead(LeadStatus.NEW, "otro", "algo raro", NOW.minusDays(1)),
        lead(LeadStatus.NEW, "categoria_inexistente", "vaya a saber", NOW.minusDays(1)),
        lead(LeadStatus.NEW, null, "sin clasificar todavía", NOW.minusDays(1)),
        lead(LeadStatus.NEW, "plomeria", "pierde la canilla", NOW.minusDays(1))
    );

    PublicDemandResponse response = service.get();

    assertThat(response.totalOpen()).isEqualTo(1);
    assertThat(response.categories()).extracting(PublicDemandResponse.Item::category)
        .containsExactly("plomeria");
  }

  @Test
  void sinDemandaDevuelveListaVaciaYNoRompe() {
    PublicDemandResponse response = service.get();

    assertThat(response.totalOpen()).isZero();
    assertThat(response.categories()).isEmpty();
  }

  @Test
  void leadSinFechaDeCreacionNoSeCuenta() {
    given(lead(LeadStatus.NEW, "mandados", "sin createdAt", null));

    assertThat(service.get().totalOpen()).isZero();
  }
}
