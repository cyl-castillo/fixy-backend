package com.fixy.backend.repository;

import com.fixy.backend.model.Provider;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderRepository extends JpaRepository<Provider, Long> {
  List<Provider> findAllByOrderByCreatedAtDesc();
}
