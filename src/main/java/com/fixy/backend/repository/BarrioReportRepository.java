package com.fixy.backend.repository;

import com.fixy.backend.model.BarrioReport;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BarrioReportRepository extends JpaRepository<BarrioReport, Long> {

  List<BarrioReport> findByStatusInOrderByCreatedAtAsc(Collection<String> statuses);

  List<BarrioReport> findByStatusOrderByCreatedAtAsc(String status);

  List<BarrioReport> findByStatusAndCategory(String status, String category);

  long countByStatus(String status);
}
