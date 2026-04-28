package com.fixy.backend.repository;

import com.fixy.backend.model.ProviderLead;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderLeadRepository extends JpaRepository<ProviderLead, Long> {
  List<ProviderLead> findAllByOrderByCreatedAtDesc();
}
