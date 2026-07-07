package com.fixy.backend.repository;

import com.fixy.backend.model.UserLead;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserLeadRepository extends JpaRepository<UserLead, Long> {
  Optional<UserLead> findByUserIdAndLeadId(Long userId, Long leadId);
  List<UserLead> findByUserIdOrderByCreatedAtDesc(Long userId);
}
