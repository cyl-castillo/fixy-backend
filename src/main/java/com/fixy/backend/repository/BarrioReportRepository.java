package com.fixy.backend.repository;

import com.fixy.backend.model.BarrioReport;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BarrioReportRepository extends JpaRepository<BarrioReport, Long> {

  List<BarrioReport> findByStatusInOrderByCreatedAtAsc(Collection<String> statuses);

  List<BarrioReport> findByStatusOrderByCreatedAtAsc(String status);

  List<BarrioReport> findByStatusAndCategory(String status, String category);

  /** Candidatos de la IA revisora: sin veredicto (null) o con ERROR, con intentos disponibles. */
  @Query("select r from BarrioReport r where r.status = 'PENDING_REVIEW' and r.aiAttempts < :maxAttempts"
      + " and (r.aiVerdict is null or r.aiVerdict = 'ERROR') order by r.createdAt asc")
  List<BarrioReport> findAiCandidates(@Param("maxAttempts") int maxAttempts, Pageable pageable);

  /** Reportes que la IA llegó a opinar (APROBAR / DUDOSO): base de la métrica de coincidencia. */
  List<BarrioReport> findByAiVerdictIn(Collection<String> verdicts);

  long countByStatus(String status);
}
