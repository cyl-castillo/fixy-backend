package com.fixy.backend.service;

import com.fixy.backend.dto.OpsDailyMetricsResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Cálculo on-the-fly (sin tabla de snapshot) de métricas de negocio para el
 * panel de ops. Todo el cálculo es sobre datos ya existentes (Lead,
 * LeadEvent) vía queries de agregación simples + post-procesamiento en Java.
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

  private static final String EVENT_PROVIDER_CONTACTED = "PROVIDER_CONTACTED";
  private static final String EVENT_PROVIDER_ACCEPTED = "PROVIDER_ACCEPTED";
  private static final String EVENT_PROVIDER_REJECTED = "PROVIDER_REJECTED";

  private final LeadRepository leadRepository;
  private final LeadEventRepository leadEventRepository;

  public OpsMetricsService(LeadRepository leadRepository, LeadEventRepository leadEventRepository) {
    this.leadRepository = leadRepository;
    this.leadEventRepository = leadEventRepository;
  }

  public OpsDailyMetricsResponse dailyMetrics(OffsetDateTime from, OffsetDateTime to) {
    List<Lead> leadsInRange = leadRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to);

    long totalLeadsCreated = leadsInRange.size();

    double fillRatePercentage = computeFillRatePercentage(leadsInRange);
    Map<String, Long> leadsByStatus = computeLeadsByStatus(leadsInRange);

    List<Long> responseTimesSeconds = computeFirstResponseTimesSeconds(leadsInRange);
    Long medianSeconds = median(responseTimesSeconds);

    RepeatRateResult repeatRateResult = computeRepeatRateAutodeclared(leadsInRange);

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
        repeatRateResult.percentage()
    );
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
   * PROVIDER_CONTACTED y la PRIMERA respuesta (PROVIDER_ACCEPTED o
   * PROVIDER_REJECTED) que ocurra DESPUÉS de ese primer contacto.
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

        boolean isResponse = EVENT_PROVIDER_ACCEPTED.equals(event.getType())
            || EVENT_PROVIDER_REJECTED.equals(event.getType());

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
