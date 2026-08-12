package com.fixy.backend.repository;

import com.fixy.backend.model.PushSubscription;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {
  List<PushSubscription> findByLeadId(Long leadId);

  List<PushSubscription> findByProviderId(Long providerId);

  boolean existsByLeadIdAndEndpoint(Long leadId, String endpoint);

  boolean existsByProviderIdAndEndpoint(Long providerId, String endpoint);

  /** Suscripciones de cliente (nunca de proveedor) — universo del digest de ofertas por zona. */
  List<PushSubscription> findByLeadIdIsNotNull();

  /** Candidatas al backfill de zona: de cliente, sin zona resuelta todavía. */
  List<PushSubscription> findByLeadIdIsNotNullAndZoneIsNull();
}
