package com.fixy.backend.service;

import com.fixy.backend.dto.OpsDailyMetricsResponse;
import com.fixy.backend.dto.OpsDailyMetricsResponse.Alerts;
import com.fixy.backend.dto.OpsDailyMetricsResponse.CategoryHealth;
import com.fixy.backend.dto.OpsDailyMetricsResponse.Day30Gate;
import com.fixy.backend.dto.OpsDailyMetricsResponse.Day60Gate;
import com.fixy.backend.dto.OpsDailyMetricsResponse.Day90Gate;
import com.fixy.backend.dto.OpsDailyMetricsResponse.Funnel;
import com.fixy.backend.dto.OpsDailyMetricsResponse.Gates;
import com.fixy.backend.dto.OpsDailyMetricsResponse.OfferResponses;
import com.fixy.backend.dto.OpsDailyMetricsResponse.ProviderResponseStats;
import com.fixy.backend.model.CustomerPayment;
import com.fixy.backend.model.CustomerPaymentKind;
import com.fixy.backend.model.CustomerPaymentStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadRating;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderOffer;
import com.fixy.backend.model.ProviderOfferResponse;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.model.SmokeTraffic;
import com.fixy.backend.repository.CustomerPaymentRepository;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRatingRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderOfferRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Cálculo on-the-fly (sin tabla de snapshot) de métricas de negocio para el
 * panel de ops. Todo el cálculo es sobre datos ya existentes (Lead,
 * LeadEvent, ProviderOffer, LeadRating, CustomerPayment) vía queries de
 * agregación simples + post-procesamiento en Java.
 *
 * Follow-up explícito: si el volumen de leads/eventos crece mucho, este
 * cálculo on-the-fly (trae todos los leads y eventos del rango a memoria)
 * puede volverse pesado. Hoy NO se implementa una tabla de snapshot
 * pre-calculada — queda como mejora futura si el volumen lo justifica.
 */
@Service
public class OpsMetricsService {

  private static final Set<LeadStatus> FILLED_STATUSES =
      Set.of(LeadStatus.ASSIGNED, LeadStatus.IN_PROGRESS, LeadStatus.COMPLETED);

  /** Mismos "abiertos sin proveedor aceptado" que ProviderOpportunityService.OPEN_STATUSES —
   * ASSIGNED en adelante ya implica que alguien aceptó, así que no es backlog. */
  private static final Set<LeadStatus> STALLED_CANDIDATE_STATUSES =
      Set.of(LeadStatus.NEW, LeadStatus.IN_REVIEW, LeadStatus.PROVIDER_CONTACTED);
  private static final long STALLED_THRESHOLD_HOURS = 48;

  private static final String EVENT_PROVIDER_CONTACTED = "PROVIDER_CONTACTED";
  private static final String EVENT_PROVIDER_ACCEPTED = "PROVIDER_ACCEPTED";
  private static final String EVENT_PROVIDER_DECLINED = "PROVIDER_DECLINED";
  private static final String EVENT_PROVIDER_STATUS_CHANGE = "PROVIDER_STATUS_CHANGE";
  private static final String ASSIGNED_SUFFIX = "→ ASSIGNED";
  private static final String ACTOR_PROVIDER = "provider";

  /** Tier 3 (contrato §A.6): los 5 tipos de evento que alimentan "alertas". */
  private static final List<String> ALERT_EVENT_TYPES = List.of(
      "SEARCH_DEADLINE_MISSED", "MUTE_LEAD_NOTIFIED", "REVIEW_REQUESTED",
      "PRICE_CHANGE_PROPOSED", "PRICE_CHANGE_REJECTED");

  private final LeadRepository leadRepository;
  private final LeadEventRepository leadEventRepository;
  private final LeadMessageRepository leadMessageRepository;
  private final CustomerPaymentRepository customerPaymentRepository;
  private final LeadRatingRepository leadRatingRepository;
  private final ProviderRepository providerRepository;
  private final ProviderOfferRepository providerOfferRepository;
  private final ProviderCatalogService providerCatalogService;
  private final ServiceCatalogService serviceCatalogService;
  private final Clock clock;

  public OpsMetricsService(
      LeadRepository leadRepository, LeadEventRepository leadEventRepository,
      LeadMessageRepository leadMessageRepository,
      CustomerPaymentRepository customerPaymentRepository,
      LeadRatingRepository leadRatingRepository,
      ProviderRepository providerRepository,
      ProviderOfferRepository providerOfferRepository,
      ProviderCatalogService providerCatalogService,
      ServiceCatalogService serviceCatalogService,
      Clock clock
  ) {
    this.leadRepository = leadRepository;
    this.leadEventRepository = leadEventRepository;
    this.leadMessageRepository = leadMessageRepository;
    this.customerPaymentRepository = customerPaymentRepository;
    this.leadRatingRepository = leadRatingRepository;
    this.providerRepository = providerRepository;
    this.providerOfferRepository = providerOfferRepository;
    this.providerCatalogService = providerCatalogService;
    this.serviceCatalogService = serviceCatalogService;
    this.clock = clock;
  }

  public OpsDailyMetricsResponse dailyMetrics(OffsetDateTime from, OffsetDateTime to) {
    // Tráfico [smoke] fuera desde acá: hallazgo 2026-08-17 (lead #200, cierre
    // de $1 con comisión condonada "Prueba" contando como cierre real en fill
    // rate y repeat rate). Se filtra ANTES de cualquier cálculo para que
    // totalLeadsCreated, leadsByStatus, fill rate, tiempo de respuesta y
    // repeat rate queden todos consistentes entre sí.
    //
    // Nota sobre WAIVED: NO se excluye por sí sola acá. Un cierre real
    // (lead no-smoke) con comisión condonada como cortesía comercial sigue
    // siendo un cierre real — mismo criterio que Provider.completedJobsCount
    // (ver ProviderSelfService.updateLeadStatus). Solo el tráfico sintético
    // infla las métricas; una condonación sobre trabajo real no miente.
    List<Lead> leadsInRange = leadRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to).stream()
        .filter(lead -> !isSmoke(lead))
        .toList();

    long totalLeadsCreated = leadsInRange.size();

    double fillRatePercentage = computeFillRatePercentage(leadsInRange);
    Map<String, Long> leadsByStatus = computeLeadsByStatus(leadsInRange);

    List<Long> responseTimesSeconds = computeFirstResponseTimesSeconds(leadsInRange);
    Long medianSeconds = median(responseTimesSeconds);

    RepeatRateResult repeatRateResult = computeRepeatRateAutodeclared(leadsInRange);

    // Refundación de Fixy, fase 1 (contrato §5, "métrica pedidos reales"):
    // cuántos leads son pedido real (no un chat que nunca arrancó) y cuánto
    // ruido de chats vacíos se está eliminando con el pedido estructurado.
    Set<Long> leadIdsWithCustomerMessage = leadIdsWithCustomerMessage(leadsInRange);

    // Lista (no solo conteo) porque Tier 3 la reusa para fillRate2h y para
    // la salud por categoría — un solo criterio de "pedido real", sin
    // duplicarlo entre secciones.
    List<Lead> realLeads = leadsInRange.stream()
        .filter(lead -> hasText(lead.getDetectedCategory()))
        // Un pedido estructurado (home o plan Casa a distancia) es real por
        // definición: trajo servicio, zona y teléfono sin necesidad de chat.
        .filter(lead -> leadIdsWithCustomerMessage.contains(lead.getId()) || hasText(lead.getServiceCode()))
        .toList();
    long realRequests = realLeads.size();
    long structuredOrders = leadsInRange.stream()
        .filter(lead -> hasText(lead.getServiceCode()))
        .count();
    long completedJobs = leadsInRange.stream()
        .filter(lead -> lead.getStatus() == LeadStatus.COMPLETED)
        .count();
    // Un pedido estructurado nace sin mensajes del cliente por diseño (el
    // formulario ya trajo todo): no es un chat vacío, es un pedido.
    long emptyChats = leadsInRange.stream()
        .filter(lead -> lead.getServiceCode() == null)
        .filter(lead -> !leadIdsWithCustomerMessage.contains(lead.getId()))
        .count();

    // Refundación fase 2 (contrato §A.4.6): cargos de servicio creados vs.
    // cobrados. "Creados" mide por created_at (cuándo se generó el cargo);
    // "cobrados" mide por paid_at (cuándo se pagó), no necesariamente el
    // mismo — un cargo creado el 30 puede pagarse el 2 del mes siguiente,
    // así que ambos filtran independientemente sobre la ventana.
    long serviceFeesCreated = customerPaymentRepository
        .findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to).stream()
        .filter(p -> p.getKind() == CustomerPaymentKind.SERVICE_FEE)
        .count();
    java.math.BigDecimal serviceFeesCollected = customerPaymentRepository
        .findByPaidAtGreaterThanEqualAndPaidAtLessThan(from, to).stream()
        .filter(p -> p.getKind() == CustomerPaymentKind.SERVICE_FEE)
        .map(CustomerPayment::getAmount)
        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

    // ---- Tier 3 (contrato §A): embudo, compuertas, proveedores, categorías ----

    List<Long> leadIds = leadsInRange.stream().map(Lead::getId).toList();

    Map<Long, List<LeadEvent>> eventsByLeadId = leadIds.isEmpty()
        ? Map.of()
        : leadEventRepository.findByLeadIdInOrderByLeadIdAscCreatedAtAsc(leadIds).stream()
            .collect(Collectors.groupingBy(event -> event.getLead().getId()));

    Set<Long> leadIdsWithAnyOffer = leadIds.isEmpty()
        ? Set.of()
        : providerOfferRepository.findByLeadIdIn(leadIds).stream()
            .map(ProviderOffer::getLeadId)
            .collect(Collectors.toSet());

    Map<Long, OffsetDateTime> firstAcceptanceAtByLeadId = firstAcceptanceEventTimeByLeadId(eventsByLeadId);

    Set<Long> paidLeadIds = leadIds.isEmpty()
        ? Set.of()
        : customerPaymentRepository
            .findByLeadIdInAndKindAndStatus(leadIds, CustomerPaymentKind.SERVICE_FEE, CustomerPaymentStatus.PAID)
            .stream()
            .map(CustomerPayment::getLeadId)
            .collect(Collectors.toSet());

    List<LeadRating> ratingsForLeadsInRange = leadIds.isEmpty()
        ? List.of()
        : leadRatingRepository.findByLeadIdIn(leadIds);
    Set<Long> reviewedLeadIds = ratingsForLeadsInRange.stream()
        .map(LeadRating::getLeadId).collect(Collectors.toSet());
    Set<Long> verifiedReviewedLeadIds = ratingsForLeadsInRange.stream()
        .filter(LeadRating::isVerified)
        .map(LeadRating::getLeadId).collect(Collectors.toSet());

    Set<Long> assignedLeadIds = leadsInRange.stream()
        .filter(lead -> FILLED_STATUSES.contains(lead.getStatus()) || firstAcceptanceAtByLeadId.containsKey(lead.getId()))
        .map(Lead::getId)
        .collect(Collectors.toSet());

    Set<Long> contactedLeadIds = leadsInRange.stream()
        .filter(lead -> leadIdsWithAnyOffer.contains(lead.getId())
            || eventsByLeadId.getOrDefault(lead.getId(), List.of()).stream()
                .anyMatch(event -> EVENT_PROVIDER_CONTACTED.equals(event.getType())))
        .map(Lead::getId)
        .collect(Collectors.toSet());

    Funnel funnel = new Funnel(
        totalLeadsCreated,
        realRequests,
        contactedLeadIds.size(),
        assignedLeadIds.size(),
        completedJobs,
        paidLeadIds.size(),
        reviewedLeadIds.size(),
        verifiedReviewedLeadIds.size()
    );

    Double fillRate2hPercentage = fillRate2hPercentage(realLeads, firstAcceptanceAtByLeadId);

    // Ofertas EN el rango pedido (por offeredAt, no por el lead) — base de
    // offerResponses, medianOfferResponseMinutes, "providers" y "categories".
    List<ProviderOffer> offersInRange =
        providerOfferRepository.findByOfferedAtGreaterThanEqualAndOfferedAtLessThan(from, to);

    Map<Long, Lead> leadsById = new HashMap<>();
    for (Lead lead : leadsInRange) {
      leadsById.put(lead.getId(), lead);
    }
    Set<Long> offerLeadIdsToLoad = offersInRange.stream()
        .map(ProviderOffer::getLeadId)
        .filter(id -> !leadsById.containsKey(id))
        .collect(Collectors.toSet());
    if (!offerLeadIdsToLoad.isEmpty()) {
      for (Lead lead : leadRepository.findAllById(offerLeadIdsToLoad)) {
        leadsById.put(lead.getId(), lead);
      }
    }
    // "Todo excluye smoke" (contrato, línea de apertura): una oferta de un
    // lead sintético no puede inflar ni el score de respuesta ni la salud
    // de categoría.
    List<ProviderOffer> offersInRangeNonSmoke = offersInRange.stream()
        .filter(offer -> {
          Lead lead = leadsById.get(offer.getLeadId());
          return lead == null || !isSmoke(lead);
        })
        .toList();

    OfferResponses offerResponses = computeOfferResponses(offersInRangeNonSmoke);
    Integer medianOfferResponseMinutes = medianResponseMinutes(offersInRangeNonSmoke);

    List<ProviderResponseStats> providers = computeProviders(offersInRangeNonSmoke, leadsInRange);
    List<CategoryHealth> categories = computeCategories(
        leadsInRange, realLeads, assignedLeadIds, firstAcceptanceAtByLeadId, offersInRangeNonSmoke, leadsById);

    Alerts alerts = computeAlerts(from, to);
    Gates gates = computeGates(
        funnel, fillRate2hPercentage, medianOfferResponseMinutes, categories, repeatRateResult,
        ratingsForLeadsInRange);

    return new OpsDailyMetricsResponse(
        from,
        to,
        totalLeadsCreated,
        fillRatePercentage,
        leadsByStatus,
        medianSeconds,
        responseTimesSeconds.size(),
        repeatRateResult.distinctClientsWithCompleted(),
        repeatRateResult.repeatClients(),
        repeatRateResult.percentage(),
        computeStalledLeads48h(),
        realRequests,
        structuredOrders,
        completedJobs,
        emptyChats,
        serviceFeesCreated,
        serviceFeesCollected,
        funnel,
        fillRate2hPercentage,
        medianOfferResponseMinutes,
        offerResponses,
        providers,
        categories,
        alerts,
        gates
    );
  }

  /** Ids de leads (del subconjunto dado) que tienen al menos un mensaje con
   * sender="customer" — batched para evitar N+1 (mismo patrón que
   * computeFirstResponseTimesSeconds con LeadEvent). */
  private Set<Long> leadIdsWithCustomerMessage(List<Lead> leads) {
    List<Long> ids = leads.stream().map(Lead::getId).toList();
    if (ids.isEmpty()) {
      return Set.of();
    }
    return leadMessageRepository.findByLeadIdInAndSender(ids, "customer").stream()
        .map(com.fixy.backend.model.LeadMessage::getLeadId)
        .collect(Collectors.toSet());
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isBlank();
  }

  private boolean isSmoke(Lead lead) {
    return SmokeTraffic.marks(lead.getProblem());
  }

  /**
   * Backlog ACTUAL (no atado a la ventana from/to pedida): leads abiertos sin
   * proveedor que haya aceptado, con más de 48h desde su creación, sin
   * contar smoke. Es "cuántos pedidos reales están colgados ahora mismo",
   * útil incluso si from/to apunta a una semana vieja — por eso usa su
   * propio Clock en vez de derivar "ahora" de `to`.
   */
  private int computeStalledLeads48h() {
    OffsetDateTime cutoff = OffsetDateTime.now(clock).minusHours(STALLED_THRESHOLD_HOURS);
    int count = 0;
    for (LeadStatus status : STALLED_CANDIDATE_STATUSES) {
      for (Lead lead : leadRepository.findByStatusOrderByCreatedAtDesc(status)) {
        if (isSmoke(lead)) {
          continue;
        }
        if (lead.getCreatedAt() != null && lead.getCreatedAt().isBefore(cutoff)) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * Fill rate = % de leads (creados en el rango) cuyo status ACTUAL está en
   * {ASSIGNED, IN_PROGRESS, COMPLETED}.
   *
   * Simplificación explícita: no existe una tabla de historial de estados,
   * solo el status actual de cada Lead. Un lead que llegó a estar ASSIGNED
   * y luego fue CANCELLED hoy no se puede distinguir de uno que nunca llegó
   * a ASSIGNED, porque solo tenemos el status vigente. Por eso "fill rate"
   * se define acá como el status actual, no como "llegó a estar asignado
   * alguna vez". Si se necesita el historial completo, habría que agregar
   * una tabla de historial de estados (fuera de alcance de esta épica).
   */
  private double computeFillRatePercentage(List<Lead> leadsInRange) {
    if (leadsInRange.isEmpty()) {
      return 0.0;
    }

    long filled = leadsInRange.stream()
        .filter(lead -> FILLED_STATUSES.contains(lead.getStatus()))
        .count();

    return percentage(filled, leadsInRange.size());
  }

  private Map<String, Long> computeLeadsByStatus(List<Lead> leadsInRange) {
    Map<LeadStatus, Long> counts = new EnumMap<>(LeadStatus.class);
    for (LeadStatus status : LeadStatus.values()) {
      counts.put(status, 0L);
    }
    for (Lead lead : leadsInRange) {
      counts.merge(lead.getStatus(), 1L, Long::sum);
    }

    return counts.entrySet().stream()
        .collect(Collectors.toMap(entry -> entry.getKey().name(), Map.Entry::getValue));
  }

  /**
   * Para cada lead, calcula el tiempo (en segundos) entre su PRIMER evento
   * PROVIDER_CONTACTED y la PRIMERA respuesta posterior a ese primer
   * contacto.
   *
   * Tier 3 (contrato §A.3, corrección): "respuesta" antes buscaba
   * PROVIDER_ACCEPTED/PROVIDER_REJECTED — el reject nunca existió como tipo
   * de evento en ningún camino real, así que la mitad de los casos (rechazo)
   * jamás cerraba y esta métrica quedaba sesgada/vacía. Ahora una respuesta
   * es: PROVIDER_ACCEPTED (bandeja/WhatsApp), PROVIDER_STATUS_CHANGE de
   * actor "provider" con mensaje que termina en "→ ASSIGNED" (panel
   * self-service), o PROVIDER_DECLINED (rechazo, cualquier camino). Mismo
   * criterio de aceptación que {@link #isAcceptanceEvent}.
   *
   * Un lead puede tener varios PROVIDER_CONTACTED secuenciales (varios
   * proveedores contactados uno tras otro). El tiempo de respuesta del lead
   * se define siempre respecto del PRIMER contacto, no del último — el
   * criterio de negocio es "cuánto tardamos en obtener una respuesta desde
   * que arrancamos a buscar proveedor para este lead", no el tiempo del
   * contacto puntual que terminó respondiendo.
   *
   * Leads sin ninguna respuesta después del primer contacto quedan
   * excluidos de la lista devuelta (no se cuentan como 0).
   */
  private List<Long> computeFirstResponseTimesSeconds(List<Lead> leadsInRange) {
    if (leadsInRange.isEmpty()) {
      return List.of();
    }

    List<Long> leadIds = leadsInRange.stream().map(Lead::getId).toList();
    List<LeadEvent> events = leadEventRepository.findByLeadIdInOrderByLeadIdAscCreatedAtAsc(leadIds);

    Map<Long, List<LeadEvent>> eventsByLead = events.stream()
        .collect(Collectors.groupingBy(event -> event.getLead().getId()));

    List<Long> responseTimes = new ArrayList<>();

    for (List<LeadEvent> leadEvents : eventsByLead.values()) {
      // Ya vienen ordenados por createdAt asc gracias al query, pero no
      // asumimos orden estable entre leads distintos agrupados por stream.
      List<LeadEvent> ordered = leadEvents.stream()
          .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
          .toList();

      OffsetDateTime firstContactedAt = null;
      OffsetDateTime firstResponseAt = null;

      for (LeadEvent event : ordered) {
        if (firstContactedAt == null) {
          if (EVENT_PROVIDER_CONTACTED.equals(event.getType())) {
            firstContactedAt = event.getCreatedAt();
          }
          continue;
        }

        boolean isResponse = isAcceptanceEvent(event) || EVENT_PROVIDER_DECLINED.equals(event.getType());

        if (isResponse) {
          firstResponseAt = event.getCreatedAt();
          break;
        }
      }

      if (firstContactedAt != null && firstResponseAt != null) {
        responseTimes.add(Duration.between(firstContactedAt, firstResponseAt).getSeconds());
      }
    }

    return responseTimes;
  }

  /** Tier 3 (contrato §A.1/§A.2): ¿este evento es una "aceptación" del
   * proveedor? PROVIDER_ACCEPTED (bandeja/WhatsApp, ver
   * LeadAssignmentService) o PROVIDER_STATUS_CHANGE de actor "provider" con
   * mensaje "... → ASSIGNED" (panel self-service, ver
   * ProviderSelfService.updateLeadStatus). Fuente única para "assigned" en
   * el embudo, fillRate2h y la corrección de medianTimeToFirstResponseSeconds. */
  private boolean isAcceptanceEvent(LeadEvent event) {
    if (EVENT_PROVIDER_ACCEPTED.equals(event.getType())) {
      return true;
    }
    return EVENT_PROVIDER_STATUS_CHANGE.equals(event.getType())
        && ACTOR_PROVIDER.equals(event.getActor())
        && event.getMessage() != null
        && event.getMessage().endsWith(ASSIGNED_SUFFIX);
  }

  /** Primer momento de aceptación de cada lead (mínimo entre sus eventos de
   * aceptación), o ausente si nunca se aceptó. Base de "assigned" (funnel) y
   * de fillRate2h. */
  private Map<Long, OffsetDateTime> firstAcceptanceEventTimeByLeadId(Map<Long, List<LeadEvent>> eventsByLeadId) {
    Map<Long, OffsetDateTime> result = new HashMap<>();
    for (Map.Entry<Long, List<LeadEvent>> entry : eventsByLeadId.entrySet()) {
      OffsetDateTime earliest = null;
      for (LeadEvent event : entry.getValue()) {
        if (!isAcceptanceEvent(event)) {
          continue;
        }
        if (earliest == null || event.getCreatedAt().isBefore(earliest)) {
          earliest = event.getCreatedAt();
        }
      }
      if (earliest != null) {
        result.put(entry.getKey(), earliest);
      }
    }
    return result;
  }

  /** Tier 3 (contrato §A.2): % de {@code realLeadsSubset} que llegó a
   * "aceptado" dentro de las 2h desde su creación. null si el subconjunto
   * viene vacío (no hay pedidos reales sobre los que medir). */
  private Double fillRate2hPercentage(List<Lead> realLeadsSubset, Map<Long, OffsetDateTime> firstAcceptanceAtByLeadId) {
    if (realLeadsSubset.isEmpty()) {
      return null;
    }
    long filledWithin2h = realLeadsSubset.stream()
        .filter(lead -> {
          OffsetDateTime acceptedAt = firstAcceptanceAtByLeadId.get(lead.getId());
          return acceptedAt != null && lead.getCreatedAt() != null
              && !acceptedAt.isAfter(lead.getCreatedAt().plusHours(2));
        })
        .count();
    return percentage(filledWithin2h, realLeadsSubset.size());
  }

  private OfferResponses computeOfferResponses(List<ProviderOffer> offers) {
    long total = offers.size();
    long accepted = offers.stream().filter(o -> o.getResponse() == ProviderOfferResponse.ACCEPTED).count();
    long declined = offers.stream().filter(o -> o.getResponse() == ProviderOfferResponse.DECLINED).count();
    long timeout = offers.stream().filter(o -> o.getResponse() == ProviderOfferResponse.TIMEOUT).count();
    long pending = offers.stream().filter(o -> o.getResponse() == null).count();
    long inWindow = offers.stream().filter(ProviderOffer::isInWindow).count();
    return new OfferResponses(total, accepted, declined, timeout, pending, inWindow);
  }

  /** [accepted, declined, timeout] — contador compartido por "providers" y
   * "categories" para la tasa de aceptación. */
  private long[] responseCounts(List<ProviderOffer> offers) {
    long accepted = 0;
    long declined = 0;
    long timeout = 0;
    for (ProviderOffer offer : offers) {
      if (offer.getResponse() == ProviderOfferResponse.ACCEPTED) {
        accepted++;
      } else if (offer.getResponse() == ProviderOfferResponse.DECLINED) {
        declined++;
      } else if (offer.getResponse() == ProviderOfferResponse.TIMEOUT) {
        timeout++;
      }
    }
    return new long[] {accepted, declined, timeout};
  }

  private Double acceptanceRatePercentage(long accepted, long declined, long timeout) {
    long decided = accepted + declined + timeout;
    return decided == 0 ? null : percentage(accepted, decided);
  }

  /** Minutos de respuesta (offeredAt → respondedAt) de las ofertas EN
   * VENTANA respondidas (ACCEPTED/DECLINED) — sin cap ni prior (a diferencia
   * de {@link ProviderCatalogService#responseScoreMinutes}, que es para el
   * ranking del matching, no para este reporte de ops). */
  private List<Long> responseMinutesInWindow(List<ProviderOffer> offers) {
    return offers.stream()
        .filter(ProviderOffer::isInWindow)
        .filter(o -> o.getResponse() == ProviderOfferResponse.ACCEPTED || o.getResponse() == ProviderOfferResponse.DECLINED)
        .filter(o -> o.getRespondedAt() != null)
        .map(o -> Duration.between(o.getOfferedAt(), o.getRespondedAt()).toMinutes())
        .toList();
  }

  private Integer medianResponseMinutes(List<ProviderOffer> offers) {
    Long medianValue = median(responseMinutesInWindow(offers));
    return medianValue == null ? null : medianValue.intValue();
  }

  /** Tier 3 (contrato §A.4): estadísticas de respuesta por proveedor activo
   * (AVAILABLE + acceptingWork) o con al menos una oferta en el rango,
   * ordenadas por mediana de respuesta ascendente (null al final). */
  private List<ProviderResponseStats> computeProviders(List<ProviderOffer> offersInRangeNonSmoke, List<Lead> leadsInRange) {
    Set<Long> providerIdsWithOffers = offersInRangeNonSmoke.stream()
        .map(ProviderOffer::getProviderId).collect(Collectors.toSet());

    Map<Long, List<ProviderOffer>> offersByProvider = offersInRangeNonSmoke.stream()
        .collect(Collectors.groupingBy(ProviderOffer::getProviderId));

    List<ProviderResponseStats> stats = providerRepository.findAll().stream()
        .filter(provider -> isActiveAcceptingWork(provider) || providerIdsWithOffers.contains(provider.getId()))
        .map(provider -> toProviderResponseStats(
            provider, offersByProvider.getOrDefault(provider.getId(), List.of()), leadsInRange))
        .collect(Collectors.toCollection(ArrayList::new));

    stats.sort(Comparator.comparing(
        ProviderResponseStats::medianResponseMinutes, Comparator.nullsLast(Comparator.naturalOrder())));
    return stats;
  }

  private ProviderResponseStats toProviderResponseStats(Provider provider, List<ProviderOffer> offers, List<Lead> leadsInRange) {
    long[] counts = responseCounts(offers);
    long accepted = counts[0];
    long declined = counts[1];
    long timeout = counts[2];
    long completedInRange = leadsInRange.stream()
        .filter(lead -> provider.getId().equals(lead.getAssignedProviderId()) && lead.getStatus() == LeadStatus.COMPLETED)
        .count();
    int ratingCount = provider.getRatingCount() == null ? 0 : provider.getRatingCount();
    Double ratingAverage = ratingCount == 0 ? null : provider.getRatingAverage();

    return new ProviderResponseStats(
        provider.getId(),
        provider.getName(),
        splitCsv(provider.getCategories()),
        providerCatalogService.isOpenNow(provider),
        blankToNull(provider.getAvailabilityWindows()),
        offers.size(),
        accepted,
        declined,
        timeout,
        acceptanceRatePercentage(accepted, declined, timeout),
        medianResponseMinutes(offers),
        completedInRange,
        ratingAverage,
        ratingCount
    );
  }

  /** AVAILABLE + acceptingWork (o null, default histórico). Desvío
   * documentado del contrato (dice "AVAILABLE/ACTIVE", pero {@link
   * ProviderStatus} no tiene ACTIVE — mismo criterio que
   * {@code ProviderCatalogService.availableForNewWork}, ver
   * TIER2_CONTRATO.md "Cambios durante implementación"). */
  private boolean isActiveAcceptingWork(Provider provider) {
    boolean paused = provider.getAcceptingWork() != null && !provider.getAcceptingWork();
    return provider.getStatus() == ProviderStatus.AVAILABLE && !paused;
  }

  /** Tier 3 (contrato §A.5): salud por categoría activa
   * ({@code fixy.orders.active-categories}). */
  private List<CategoryHealth> computeCategories(
      List<Lead> leadsInRange, List<Lead> realLeads, Set<Long> assignedLeadIds,
      Map<Long, OffsetDateTime> firstAcceptanceAtByLeadId, List<ProviderOffer> offersInRangeNonSmoke,
      Map<Long, Lead> leadsById
  ) {
    Set<Long> realLeadIds = realLeads.stream().map(Lead::getId).collect(Collectors.toSet());
    List<Provider> allProviders = providerRepository.findAll();

    List<CategoryHealth> result = new ArrayList<>();
    for (String category : serviceCatalogService.activeCategories()) {
      List<Provider> providersInCategory = allProviders.stream()
          .filter(this::isActiveAcceptingWork)
          .filter(provider -> splitCsv(provider.getCategories()).stream()
              .anyMatch(providerCategory -> providerCategory.equalsIgnoreCase(category)))
          .toList();
      long activeProviders = providersInCategory.size();
      long openNowProviders = providersInCategory.stream()
          .filter(providerCatalogService::isOpenNow)
          .count();

      List<Lead> leadsOfCategory = leadsInRange.stream()
          .filter(lead -> category.equalsIgnoreCase(lead.getDetectedCategory()))
          .toList();
      long realRequestsOfCategory = leadsOfCategory.stream()
          .filter(lead -> realLeadIds.contains(lead.getId()))
          .count();
      long assignedOfCategory = leadsOfCategory.stream()
          .filter(lead -> assignedLeadIds.contains(lead.getId()))
          .count();
      List<Lead> realLeadsOfCategory = leadsOfCategory.stream()
          .filter(lead -> realLeadIds.contains(lead.getId()))
          .toList();
      Double fillRate2hOfCategory = fillRate2hPercentage(realLeadsOfCategory, firstAcceptanceAtByLeadId);

      List<ProviderOffer> offersOfCategory = offersInRangeNonSmoke.stream()
          .filter(offer -> {
            Lead lead = leadsById.get(offer.getLeadId());
            return lead != null && category.equalsIgnoreCase(lead.getDetectedCategory());
          })
          .toList();
      long[] counts = responseCounts(offersOfCategory);

      result.add(new CategoryHealth(
          category,
          activeProviders,
          openNowProviders,
          realRequestsOfCategory,
          assignedOfCategory,
          fillRate2hOfCategory,
          acceptanceRatePercentage(counts[0], counts[1], counts[2]),
          medianResponseMinutes(offersOfCategory)
      ));
    }
    return result;
  }

  /** Tier 3 (contrato §A.6): conteos de eventos que requieren acción de ops
   * dentro de la ventana pedida, sin smoke. */
  private Alerts computeAlerts(OffsetDateTime from, OffsetDateTime to) {
    List<LeadEvent> events = leadEventRepository
        .findByCreatedAtGreaterThanEqualAndCreatedAtLessThanAndTypeIn(from, to, ALERT_EVENT_TYPES);
    Map<Long, Lead> eventLeadsById = loadLeadsFor(
        events.stream().map(event -> event.getLead().getId()).collect(Collectors.toSet()));
    List<LeadEvent> nonSmokeEvents = events.stream()
        .filter(event -> {
          Lead lead = eventLeadsById.get(event.getLead().getId());
          return lead == null || !isSmoke(lead);
        })
        .toList();

    long searchDeadlineMissed = nonSmokeEvents.stream()
        .filter(event -> "SEARCH_DEADLINE_MISSED".equals(event.getType()))
        .map(event -> event.getLead().getId())
        .distinct()
        .count();
    long muteLeads = nonSmokeEvents.stream().filter(event -> "MUTE_LEAD_NOTIFIED".equals(event.getType())).count();
    long reviewRequests = nonSmokeEvents.stream().filter(event -> "REVIEW_REQUESTED".equals(event.getType())).count();
    long priceChangesProposed = nonSmokeEvents.stream()
        .filter(event -> "PRICE_CHANGE_PROPOSED".equals(event.getType())).count();
    long priceChangesRejected = nonSmokeEvents.stream()
        .filter(event -> "PRICE_CHANGE_REJECTED".equals(event.getType())).count();

    List<LeadRating> ratingsInRange = leadRatingRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to);
    Map<Long, Lead> ratingLeadsById = loadLeadsFor(
        ratingsInRange.stream().map(LeadRating::getLeadId).collect(Collectors.toSet()));
    long lowRatings = ratingsInRange.stream()
        .filter(rating -> rating.getScore() != null && rating.getScore() <= 3)
        .filter(rating -> {
          Lead lead = ratingLeadsById.get(rating.getLeadId());
          return lead == null || !isSmoke(lead);
        })
        .count();

    return new Alerts(searchDeadlineMissed, muteLeads, lowRatings, reviewRequests, priceChangesProposed, priceChangesRejected);
  }

  private Map<Long, Lead> loadLeadsFor(Set<Long> leadIds) {
    if (leadIds.isEmpty()) {
      return Map.of();
    }
    return leadRepository.findAllById(leadIds).stream()
        .collect(Collectors.toMap(Lead::getId, lead -> lead));
  }

  /** Tier 3 (contrato §A.7): compuertas del plan de 90 días, con los números
   * crudos usados expuestos para que el frontend no recalcule. */
  private Gates computeGates(
      Funnel funnel, Double fillRate2h, Integer medianResponse, List<CategoryHealth> categories,
      RepeatRateResult repeatRateResult, List<LeadRating> ratingsForLeadsInRange
  ) {
    Boolean fillRate2hOk = fillRate2h == null ? null : fillRate2h >= 70.0;
    Boolean responseOk = medianResponse == null ? null : medianResponse < 15;
    // Vacuamente null si no hay categorías activas configuradas (no debería
    // pasar en prod, pero evita un true vacío engañoso).
    Boolean supplyOk = categories.isEmpty() ? null : categories.stream()
        .allMatch(category -> category.activeProviders() >= 5
            || (category.acceptanceRatePercentage() != null && category.acceptanceRatePercentage() >= 50));
    boolean volumeOk = funnel.assigned() >= 10;
    Day30Gate day30 = new Day30Gate(fillRate2hOk, responseOk, supplyOk, volumeOk);

    boolean completedOk = funnel.completed() >= 15;
    Double collectionPercentage = funnel.completed() == 0 ? null : percentage(funnel.paid(), funnel.completed());
    Boolean collectionOk = collectionPercentage == null ? null : collectionPercentage >= 25.0;
    Boolean collectionKill = collectionPercentage == null ? null : collectionPercentage < 15.0;
    boolean repeatOk = repeatRateResult.repeatClients() >= 1;
    Day60Gate day60 = new Day60Gate(completedOk, collectionOk, collectionKill, repeatOk);

    Boolean scaleOk;
    if (funnel.completed() < 25) {
      scaleOk = false;
    } else if (fillRate2h == null) {
      scaleOk = null;
    } else {
      scaleOk = fillRate2h >= 75.0;
    }
    Boolean repeatRateOk = repeatRateResult.distinctClientsWithCompleted() == 0
        ? null
        : repeatRateResult.percentage() >= 20.0;
    Double ratingAverage = ratingsForLeadsInRange.isEmpty()
        ? null
        : ratingsForLeadsInRange.stream().mapToInt(LeadRating::getScore).average().orElseThrow();
    Boolean ratingOk = ratingAverage == null ? null : ratingAverage >= 4.7;
    Day90Gate day90 = new Day90Gate(scaleOk, repeatRateOk, ratingOk);

    return new Gates(
        day30, day60, day90,
        fillRate2h, medianResponse, funnel.completed(), funnel.paid(), collectionPercentage,
        repeatRateResult.repeatClients(), repeatRateResult.percentage(), ratingAverage
    );
  }

  private List<String> splitCsv(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList();
  }

  private String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * Mediana calculada en Java puro sobre la lista (no SQL/percentil de DB —
   * el comportamiento de percentil difiere entre H2 y PostgreSQL). Devuelve
   * null si la lista está vacía, nunca NaN ni 0.
   */
  private Long median(List<Long> values) {
    if (values.isEmpty()) {
      return null;
    }

    List<Long> sorted = new ArrayList<>(values);
    Collections.sort(sorted);

    int size = sorted.size();
    int middle = size / 2;

    if (size % 2 == 1) {
      return sorted.get(middle);
    }

    return (sorted.get(middle - 1) + sorted.get(middle)) / 2;
  }

  /**
   * Repeat rate (versión interina, autodeclarada): % de clientes —
   * agrupados por Lead.phone normalizado — con 2 o más leads en estado
   * COMPLETED dentro de la ventana from/to.
   *
   * IMPORTANTE: hoy COMPLETED es una autodeclaración del proveedor
   * (ProviderSelfService / WhatsAppWebhookController marcan el lead como
   * COMPLETED sin que el cliente confirme nada). Cuando exista la épica
   * P0-2 y un evento tipo CUSTOMER_CONFIRMED_COMPLETION, esta métrica debe
   * recalcularse sobre esa fuente en vez de Lead.status == COMPLETED, que
   * hoy puede sobreestimar clientes recurrentes reales.
   */
  private RepeatRateResult computeRepeatRateAutodeclared(List<Lead> leadsInRange) {
    List<Lead> completedLeads = leadsInRange.stream()
        .filter(lead -> lead.getStatus() == LeadStatus.COMPLETED)
        .toList();

    Map<String, Long> completedByPhone = completedLeads.stream()
        .collect(Collectors.groupingBy(
            lead -> PhoneNumberNormalizer.normalize(lead.getPhone()),
            Collectors.counting()
        ));

    // Teléfonos vacíos ("" tras normalizar) no representan un cliente real
    // identificable — no los contamos como "cliente único" ni "repetido".
    completedByPhone.remove("");

    if (completedByPhone.isEmpty()) {
      return new RepeatRateResult(0, 0, 0.0);
    }

    long uniqueCustomers = completedByPhone.size();
    long repeatCustomers = completedByPhone.values().stream().filter(count -> count >= 2).count();

    return new RepeatRateResult(uniqueCustomers, repeatCustomers, percentage(repeatCustomers, uniqueCustomers));
  }

  private double percentage(long part, long total) {
    if (total == 0) {
      return 0.0;
    }
    return (part * 100.0) / total;
  }

  private record RepeatRateResult(long distinctClientsWithCompleted, long repeatClients, double percentage) {
  }
}
