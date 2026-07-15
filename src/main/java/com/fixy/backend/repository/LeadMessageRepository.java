package com.fixy.backend.repository;

import com.fixy.backend.model.LeadMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadMessageRepository extends JpaRepository<LeadMessage, Long> {

  List<LeadMessage> findByLeadIdOrderByCreatedAtAsc(Long leadId);

  List<LeadMessage> findByLeadIdAndIdGreaterThanOrderByCreatedAtAsc(Long leadId, Long sinceId);

  Optional<LeadMessage> findFirstByLeadIdOrderByIdDesc(Long leadId);
}
