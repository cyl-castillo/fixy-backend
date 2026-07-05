package com.fixy.backend.repository;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadRepository extends JpaRepository<Lead, Long> {
  List<Lead> findAllByOrderByCreatedAtDesc();
  List<Lead> findByStatusOrderByCreatedAtDesc(LeadStatus status);
  List<Lead> findByAssignedProviderIgnoreCaseOrderByCreatedAtDesc(String assignedProvider);
  List<Lead> findByAssignedProviderIdOrderByCreatedAtDesc(Long assignedProviderId);
  List<Lead> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(OffsetDateTime from, OffsetDateTime to);

  /** H2.4: candidatos a auto-confirmación — completados, sin disputa y sin
   * haber corrido ya el scheduler. El filtro de "sin rating" y de las 72h
   * desde el evento de completado se aplica en el servicio (requiere leer
   * LeadEvent, no es expresable acá sin un join manual). */
  List<Lead> findByStatusAndDisputedFalseAndClosingAutoConfirmedAtIsNull(LeadStatus status);
}
