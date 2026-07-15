package com.fixy.backend.repository;

import com.fixy.backend.model.PushSubscription;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {
  List<PushSubscription> findByLeadId(Long leadId);

  List<PushSubscription> findByProviderId(Long providerId);

  boolean existsByLeadIdAndEndpoint(Long leadId, String endpoint);

  boolean existsByProviderIdAndEndpoint(Long providerId, String endpoint);
}
