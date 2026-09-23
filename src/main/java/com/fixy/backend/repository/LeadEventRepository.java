package com.fixy.backend.repository;

import com.fixy.backend.model.LeadEvent;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadEventRepository extends JpaRepository<LeadEvent, Long> {
  List<LeadEvent> findByLeadIdOrderByCreatedAtAsc(Long leadId);

  /** Trae eventos de varios leads a la vez (evita N+1 al calcular métricas agregadas). */
  List<LeadEvent> findByLeadIdInOrderByLeadIdAscCreatedAtAsc(Collection<Long> leadIds);

  /** H2.4: eventos de un tipo dado para un lead, más recientes primero.
   * Se usa para encontrar cuándo pasó a COMPLETED (primer elemento de
   * findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, "PROVIDER_STATUS_CHANGE")
   * filtrado por message que termine en "→ COMPLETED" en el servicio, ya
   * que el tipo no distingue el status destino). */
  List<LeadEvent> findByLeadIdAndTypeOrderByCreatedAtDesc(Long leadId, String type);

  /** Tier 3 (contrato §A.6, alertas): eventos de los tipos pedidos cuyo
   * {@code createdAt} cae en el rango — una sola query para las 5 alertas
   * (SEARCH_DEADLINE_MISSED, MUTE_LEAD_NOTIFIED, REVIEW_REQUESTED,
   * PRICE_CHANGE_PROPOSED, PRICE_CHANGE_REJECTED), bucketeadas en el
   * servicio por tipo. */
  List<LeadEvent> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanAndTypeIn(
      OffsetDateTime from, OffsetDateTime to, Collection<String> types);
}
