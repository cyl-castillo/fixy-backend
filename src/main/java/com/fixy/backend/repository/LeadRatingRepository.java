package com.fixy.backend.repository;

import com.fixy.backend.model.LeadRating;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LeadRatingRepository extends JpaRepository<LeadRating, Long> {
  Optional<LeadRating> findByLeadId(Long leadId);
  boolean existsByLeadId(Long leadId);
  List<LeadRating> findByProviderId(Long providerId);

  /** H2.3: agregación real (no incremental) para recalcular
   * Provider.ratingAverage/ratingCount. A este volumen (leads por
   * proveedor en decenas/cientos, no millones) un COUNT+AVG por escritura
   * es más simple y menos propenso a bugs de arrastre que ir sumando en
   * memoria. */
  @Query("SELECT AVG(r.score) FROM LeadRating r WHERE r.providerId = :providerId")
  Double averageScoreByProviderId(Long providerId);

  long countByProviderId(Long providerId);
}
