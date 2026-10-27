package com.fixy.backend.repository;

import com.fixy.backend.model.BarrioClosureClaim;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BarrioClosureClaimRepository extends JpaRepository<BarrioClosureClaim, Long> {

  List<BarrioClosureClaim> findByStatusOrderByCreatedAtAsc(String status);

  List<BarrioClosureClaim> findByReportIdAndStatus(Long reportId, String status);
}
