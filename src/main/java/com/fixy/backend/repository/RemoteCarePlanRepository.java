package com.fixy.backend.repository;

import com.fixy.backend.model.RemoteCarePlan;
import com.fixy.backend.model.RemoteCarePlanStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RemoteCarePlanRepository extends JpaRepository<RemoteCarePlan, Long> {

  List<RemoteCarePlan> findAllByOrderByCreatedAtDesc();

  List<RemoteCarePlan> findByStatus(RemoteCarePlanStatus status);

  Optional<RemoteCarePlan> findByIdAndAccessToken(Long id, String accessToken);

  /** Facturación mensual (contrato §B.3): planes ACTIVE con last_billed_at
   * vencido hace 30 días o más. */
  List<RemoteCarePlan> findByStatusAndLastBilledAtLessThanEqual(
      RemoteCarePlanStatus status, OffsetDateTime cutoff);
}
