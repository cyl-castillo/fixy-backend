package com.fixy.backend.repository;

import com.fixy.backend.model.PushSubscription;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {
  List<PushSubscription> findByLeadId(Long leadId);

  List<PushSubscription> findByProviderId(Long providerId);

  boolean existsByLeadIdAndEndpoint(Long leadId, String endpoint);

  boolean existsByProviderIdAndEndpoint(Long providerId, String endpoint);

  /** Upsert por endpoint (Fase Push-2): mismo dispositivo = misma fila, sin importar de quién sea. */
  Optional<PushSubscription> findByEndpoint(String endpoint);

  /**
   * Upsert tolerante a duplicados (hotfix 2026-08-25): prod arrastra filas
   * repetidas del mismo endpoint de la era pre-upsert (cada re-suscripción
   * insertaba una nueva) y {@link #findByEndpoint} tira NonUniqueResult.
   * El upsert las trae todas, se queda con una y borra el resto.
   */
  List<PushSubscription> findAllByEndpointOrderByCreatedAtDesc(String endpoint);

  /** Universo del digest de ofertas por zona: clientes Y visitantes — nunca proveedor (Fase Push-2). */
  List<PushSubscription> findByProviderIdIsNull();

  /** Candidatas al backfill de zona: de cliente, sin zona resuelta todavía. */
  List<PushSubscription> findByLeadIdIsNotNullAndZoneIsNull();

  /** Universo del recordatorio de guardadas por vencer (Fase Push-2): tiene algo guardado. */
  List<PushSubscription> findBySavedOfferIdsIsNotNull();

  /** Universo del aviso de vencimiento al dueño (Fase 5): suscripciones ligadas a ese comercio. */
  List<PushSubscription> findByBusinessId(Long businessId);
}
