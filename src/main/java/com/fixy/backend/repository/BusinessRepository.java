package com.fixy.backend.repository;

import com.fixy.backend.model.Business;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BusinessRepository extends JpaRepository<Business, Long> {
  List<Business> findAllByOrderByCreatedAtDesc();

  /**
   * Busca un comercio por su número de WhatsApp tolerando variaciones de
   * formato (con/sin 0 inicial, con/sin código país 598) — mismo criterio
   * que {@link ProviderRepository#findByContactNumber}.
   */
  @Query("SELECT b FROM Business b WHERE REPLACE(REPLACE(b.whatsappNumber, ' ', ''), '+', '') = :normalized")
  Optional<Business> findByWhatsappNumber(String normalized);

  /**
   * Find-or-create de la ingesta automática (OfferService.ingest): el
   * scraper solo conoce el nombre curado del comercio (merchants.yaml), no
   * ids internos. Coincidencia exacta case-insensitive — a este volumen no
   * hace falta fuzzy matching, y evitarlo evita fusionar comercios distintos
   * por accidente.
   */
  Optional<Business> findByNameIgnoreCase(String name);
}
