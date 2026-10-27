package com.fixy.backend.repository;

import com.fixy.backend.model.Provider;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProviderRepository extends JpaRepository<Provider, Long> {
  List<Provider> findAllByOrderByCreatedAtDesc();

  /** Login con Google del proveedor: el sub del ID token es la llave estable de la cuenta vinculada. */
  Optional<Provider> findByGoogleSub(String googleSub);

  /**
   * Busca un provider por su número WhatsApp tolerando variaciones de
   * formato (con/sin 0 inicial, con/sin código país 598).
   */
  @Query("SELECT p FROM Provider p WHERE REPLACE(REPLACE(p.whatsappNumber, ' ', ''), '+', '') = :normalized "
      + "OR REPLACE(REPLACE(p.phone, ' ', ''), '+', '') = :normalized")
  Optional<Provider> findByContactNumber(String normalized);

  /**
   * Marca que el proveedor respondió una oferta (aceptó o rechazó). UPDATE
   * puntual y no {@code save(provider)}: el Provider que llega desde el
   * webhook de WhatsApp o desde la bandeja está desacoplado de la sesión y un
   * merge pisaría contadores/estado con valores viejos.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query("update Provider p set p.lastRespondedAt = :at where p.id = :id")
  int touchLastRespondedAt(@Param("id") Long id, @Param("at") OffsetDateTime at);
}
