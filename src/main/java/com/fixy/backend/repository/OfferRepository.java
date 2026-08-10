package com.fixy.backend.repository;

import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfferRepository extends JpaRepository<Offer, Long> {
  List<Offer> findAllByOrderByCreatedAtDesc();

  List<Offer> findByStatusOrderByCreatedAtDesc(OfferStatus status);

  List<Offer> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

  /** Candidatos del scheduler de expiración (Historia 3.4): activas ya vencidas. */
  List<Offer> findByStatusAndValidUntilBefore(OfferStatus status, OffsetDateTime cutoff);
}
