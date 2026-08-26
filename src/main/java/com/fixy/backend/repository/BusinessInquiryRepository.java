package com.fixy.backend.repository;

import com.fixy.backend.model.BusinessInquiry;
import com.fixy.backend.model.BusinessInquiryStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessInquiryRepository extends JpaRepository<BusinessInquiry, Long> {

  /** {@code GET /api/public/inquiries/{id}?token=} — 404 opaco si el token no matchea (ver BusinessInquiryService). */
  Optional<BusinessInquiry> findByIdAndAccessToken(Long id, String accessToken);

  /** {@code POST /api/public/merchant/{token}/inquiries/{id}/answer} — 404 opaco si la consulta no es de ESE comercio. */
  Optional<BusinessInquiry> findByIdAndBusinessId(Long id, Long businessId);

  /** Panel del comercio: pendientes de respuesta, más nuevas primero. */
  List<BusinessInquiry> findByBusinessIdAndStatusOrderByCreatedAtDesc(Long businessId, BusinessInquiryStatus status);

  /** Universo de {@code BusinessInquiryExpiryScheduler}: escaladas hace más de 72h. */
  List<BusinessInquiry> findByStatusAndCreatedAtBefore(BusinessInquiryStatus status, OffsetDateTime cutoff);
}
