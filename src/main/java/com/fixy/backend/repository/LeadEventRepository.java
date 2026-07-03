package com.fixy.backend.repository;

import com.fixy.backend.model.LeadEvent;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadEventRepository extends JpaRepository<LeadEvent, Long> {
  List<LeadEvent> findByLeadIdOrderByCreatedAtAsc(Long leadId);

  /** Trae eventos de varios leads a la vez (evita N+1 al calcular métricas agregadas). */
  List<LeadEvent> findByLeadIdInOrderByLeadIdAscCreatedAtAsc(Collection<Long> leadIds);
}
