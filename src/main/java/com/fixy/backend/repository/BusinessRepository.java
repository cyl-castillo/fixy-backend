package com.fixy.backend.repository;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

  /**
   * Resuelve el panel self-service del dueño (Fase 5): {@code
   * MerchantPanelService} y el upsert público de push (con {@code
   * merchantToken}) buscan por acá. {@code panelToken} es único cuando no
   * es null (ver V23), así que devuelve a lo sumo un comercio.
   */
  Optional<Business> findByPanelToken(String panelToken);

  /**
   * Resuelve la página pública del comercio (Fase 3, V26): {@code
   * PublicBusinessService} y {@code BusinessOgHtmlService} buscan por acá.
   * {@code slug} es único cuando no es null (ver V26), así que devuelve a
   * lo sumo un comercio.
   */
  Optional<Business> findBySlug(String slug);

  /** Chequeo de colisión de {@code BusinessSlugService.ensureSlug} antes de asignar un candidato. */
  boolean existsBySlug(String slug);

  /** Sitemap (Fase 3): un comercio entra recién cuando ya tiene slug — nunca
   * se fuerza {@code ensureSlug} masivamente desde el sitemap (ver
   * SitemapService). */
  List<Business> findByStatusAndSlugIsNotNull(BusinessStatus status);

  /**
   * Incremento atómico del contador de vistas de la ficha pública (Fase 3):
   * UPDATE directo en vez de read-modify-write, mismo motivo que {@code
   * OfferRepository.incrementLikeCount} — fire-and-forget concurrente sobre
   * un contador simple no debería perder increments. Devuelve cuántas filas
   * tocó (0 si el id no existe; el caller lo trata como best-effort, nunca
   * rompe la respuesta pública).
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query("update Business b set b.viewCount = b.viewCount + 1 where b.id = :id")
  int incrementViewCount(@Param("id") Long id);
}
