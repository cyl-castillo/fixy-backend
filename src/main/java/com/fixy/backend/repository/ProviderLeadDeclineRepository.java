package com.fixy.backend.repository;

import com.fixy.backend.model.ProviderLeadDecline;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderLeadDeclineRepository extends JpaRepository<ProviderLeadDecline, Long> {
  List<ProviderLeadDecline> findByProviderId(Long providerId);

  boolean existsByLeadIdAndProviderId(Long leadId, Long providerId);
}
