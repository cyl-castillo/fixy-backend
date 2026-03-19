package com.fixy.backend.repository;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadRepository extends JpaRepository<Lead, Long> {
  List<Lead> findAllByOrderByCreatedAtDesc();
  List<Lead> findByStatusOrderByCreatedAtDesc(LeadStatus status);
}
