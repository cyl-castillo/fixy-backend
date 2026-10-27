package com.fixy.backend.repository;

import com.fixy.backend.model.BarrioWaDraft;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BarrioWaDraftRepository extends JpaRepository<BarrioWaDraft, Long> {

  Optional<BarrioWaDraft> findByPhone(String phone);
}
