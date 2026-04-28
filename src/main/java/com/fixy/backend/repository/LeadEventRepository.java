package com.fixy.backend.repository;

import com.fixy.backend.model.LeadEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadEventRepository extends JpaRepository<LeadEvent, Long> {
  List<LeadEvent> findByLeadIdOrderByCreatedAtAsc(Long leadId);
}
