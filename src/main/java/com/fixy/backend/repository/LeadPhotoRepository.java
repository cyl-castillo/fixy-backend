package com.fixy.backend.repository;

import com.fixy.backend.model.LeadPhoto;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadPhotoRepository extends JpaRepository<LeadPhoto, Long> {
  List<LeadPhoto> findByLeadIdOrderByCreatedAtAsc(Long leadId);
  int countByLeadId(Long leadId);
}
