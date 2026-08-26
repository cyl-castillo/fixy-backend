package com.fixy.backend.repository;

import com.fixy.backend.model.BusinessEvent;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessEventRepository extends JpaRepository<BusinessEvent, Long> {

  /** Timeline descendente, limit vía {@link Pageable} — ver
   * GET /api/businesses/{id}/events (limit 100). */
  List<BusinessEvent> findByBusinessIdOrderByCreatedAtDesc(Long businessId, Pageable pageable);
}
