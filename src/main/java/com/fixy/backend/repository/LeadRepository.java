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
}
