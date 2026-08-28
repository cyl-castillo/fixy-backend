package com.fixy.backend.service;

import com.fixy.backend.dto.PublicDemandResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.ServiceCategory;
import com.fixy.backend.model.SmokeTraffic;
import com.fixy.backend.repository.LeadRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * "Dónde está la demanda" (mejora diaria 2026-08-27).
 *
 * <p>Dato que lo motivó: el 27/08 el tablero tenía <b>21 pedidos reales
 * abiertos y 11 de ellos eran de mandados</b> — el 52% de la demanda sin
 * cubrir — contra <b>cero proveedores reales de ese rubro</b> (el único
 * {@code AVAILABLE} en mandados era el alta de prueba del propio Carlos).
 * En paralelo, {@code /sumate} —la puerta única de registro— le ofrecía al
 * vecino que se plantea sumarse una promesa genérica ("que te encuentren en
 * tu barrio") sin decirle nunca que hay trabajo esperando. El cuello de
 * botella verificado de Fixy no es la demanda: son los proveedores. Este
 * servicio pone el número real donde se decide el alta.
 *
 * <p>Determinista y en código, no en prompt: el conteo sale de una query
 * sobre {@code Lead}, sin LLM en el camino.
 *
 * <p>Qué cuenta como "esperando proveedor":
 * <ul>
 *   <li>Status en {@link #OPEN_STATUSES} — mismo criterio de "abierto sin
 *       proveedor que haya aceptado" que usan {@code OpsMetricsService} y
 *       {@code ProviderOpportunityService}. De {@code ASSIGNED} en adelante
 *       alguien ya lo tomó.</li>
 *   <li>No {@code [smoke]} ({@link SmokeTraffic}): el tráfico sintético no
 *       puede inflar una cifra que se le muestra a un vecino.</li>
 *   <li>Creado dentro de {@link #FRESHNESS_DAYS} días. Un pedido de hace seis
 *       meses es una necesidad real que no se cubrió, pero no es honesto
 *       venderlo como trabajo disponible hoy.</li>
 *   <li>Oficio reconocido y distinto de {@code OTRO}: si no se puede dar de
 *       alta en ese rubro, mostrarlo no le sirve a nadie.</li>
 * </ul>
 */
@Service
public class PublicDemandService {

  /** Abiertos sin proveedor aceptado; espejo de {@code OpsMetricsService.STALLED_CANDIDATE_STATUSES}. */
  private static final Set<LeadStatus> OPEN_STATUSES =
      Set.of(LeadStatus.NEW, LeadStatus.IN_REVIEW, LeadStatus.PROVIDER_CONTACTED);

  /** Ventana de "demanda viva". Más viejo que esto no se ofrece como trabajo disponible. */
  static final int FRESHNESS_DAYS = 60;

  private final LeadRepository leadRepository;
  private final Clock clock;

  public PublicDemandService(LeadRepository leadRepository, Clock clock) {
    this.leadRepository = leadRepository;
    this.clock = clock;
  }

  public PublicDemandResponse get() {
    OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(FRESHNESS_DAYS);
    Map<ServiceCategory, Integer> counts = new EnumMap<>(ServiceCategory.class);

    for (LeadStatus status : OPEN_STATUSES) {
      for (Lead lead : leadRepository.findByStatusOrderByCreatedAtDesc(status)) {
        if (SmokeTraffic.marks(lead.getProblem())) {
          continue;
        }
        if (lead.getCreatedAt() == null || lead.getCreatedAt().isBefore(cutoff)) {
          continue;
        }
        resolveCategory(lead.getDetectedCategory())
            .ifPresent(category -> counts.merge(category, 1, Integer::sum));
      }
    }

    List<PublicDemandResponse.Item> items = new ArrayList<>();
    counts.forEach((category, count) ->
        items.add(new PublicDemandResponse.Item(category.id(), category.label(), count)));
    // Orden estable: más demanda primero y, a igual conteo, por id — así dos
    // llamadas seguidas devuelven siempre lo mismo (el EnumMap no alcanza).
    items.sort(Comparator.comparingInt(PublicDemandResponse.Item::openCount).reversed()
        .thenComparing(PublicDemandResponse.Item::category));

    int total = items.stream().mapToInt(PublicDemandResponse.Item::openCount).sum();
    return new PublicDemandResponse(total, List.copyOf(items));
  }

  /** Oficio del lead, solo si existe en el catálogo de alta (sin {@code OTRO} ni desconocidos). */
  private Optional<ServiceCategory> resolveCategory(String detectedCategory) {
    if (detectedCategory == null || detectedCategory.isBlank()) {
      return Optional.empty();
    }
    for (ServiceCategory category : ServiceCategory.values()) {
      if (category != ServiceCategory.OTRO && category.id().equals(detectedCategory)) {
        return Optional.of(category);
      }
    }
    return Optional.empty();
  }
}
