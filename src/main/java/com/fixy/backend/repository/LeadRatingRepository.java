package com.fixy.backend.repository;

import com.fixy.backend.model.LeadRating;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LeadRatingRepository extends JpaRepository<LeadRating, Long> {
  Optional<LeadRating> findByLeadId(Long leadId);
  boolean existsByLeadId(Long leadId);
  List<LeadRating> findByProviderId(Long providerId);

  /** Tier 3 (contrato §A.1, "reviewed"/"verifiedReviews"): reseñas de un
   * subconjunto de leads dado (los leads del rango pedido), sin filtrar por
   * la fecha de la reseña misma. */
  List<LeadRating> findByLeadIdIn(Collection<Long> leadIds);

  /** Tier 3 (contrato §A.6, alerta "lowRatings"): reseñas creadas dentro del
   * rango — el filtro score&lt;=3 se aplica en el servicio. */
  List<LeadRating> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(OffsetDateTime from, OffsetDateTime to);

  /** H2.3: agregación real (no incremental) para recalcular
   * Provider.ratingAverage/ratingCount. A este volumen (leads por
   * proveedor en decenas/cientos, no millones) un COUNT+AVG por escritura
   * es más simple y menos propenso a bugs de arrastre que ir sumando en
   * memoria. */
  @Query("SELECT AVG(r.score) FROM LeadRating r WHERE r.providerId = :providerId")
  Double averageScoreByProviderId(Long providerId);

  long countByProviderId(Long providerId);
}
