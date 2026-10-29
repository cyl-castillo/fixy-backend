package com.fixy.backend.repository;

import com.fixy.backend.model.BarrioClosureClaim;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BarrioClosureClaimRepository extends JpaRepository<BarrioClosureClaim, Long> {

  List<BarrioClosureClaim> findByStatusOrderByCreatedAtAsc(String status);

  List<BarrioClosureClaim> findByReportIdAndStatus(Long reportId, String status);

  List<BarrioClosureClaim> findByReportId(Long reportId);

  /** Candidatos de la IA de cierres: sin veredicto (null) o con ERROR, con intentos disponibles. */
  @Query("select c from BarrioClosureClaim c where c.status = 'PENDING_REVIEW' and c.aiAttempts < :maxAttempts"
      + " and (c.aiVerdict is null or c.aiVerdict = 'ERROR') order by c.createdAt asc")
  List<BarrioClosureClaim> findAiCandidates(@Param("maxAttempts") int maxAttempts, Pageable pageable);

  /** Cierres con veredicto de pruebas ya calculado (FUERTE / DEBIL): base de la métrica de coincidencia. */
  List<BarrioClosureClaim> findByEvidenceVerdictIn(Collection<String> verdicts);
}
