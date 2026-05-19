package com.fixy.backend.repository;

import com.fixy.backend.model.Provider;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProviderRepository extends JpaRepository<Provider, Long> {
  List<Provider> findAllByOrderByCreatedAtDesc();

  /**
   * Busca un provider por su número WhatsApp tolerando variaciones de
   * formato (con/sin 0 inicial, con/sin código país 598).
   */
  @Query("SELECT p FROM Provider p WHERE REPLACE(REPLACE(p.whatsappNumber, ' ', ''), '+', '') = :normalized "
      + "OR REPLACE(REPLACE(p.phone, ' ', ''), '+', '') = :normalized")
  Optional<Provider> findByContactNumber(String normalized);
}
