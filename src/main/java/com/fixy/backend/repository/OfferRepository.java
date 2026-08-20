package com.fixy.backend.repository;

import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OfferRepository extends JpaRepository<Offer, Long> {
  List<Offer> findAllByOrderByCreatedAtDesc();

  List<Offer> findByStatusOrderByCreatedAtDesc(OfferStatus status);

  List<Offer> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

  /** Candidatos del scheduler de expiración (Historia 3.4): activas ya vencidas. */
  List<Offer> findByStatusAndValidUntilBefore(OfferStatus status, OffsetDateTime cutoff);

  /** Ofertas vigentes para la superficie pública: activas y no vencidas. */
  List<Offer> findByStatusAndValidUntilAfter(OfferStatus status, OffsetDateTime cutoff);

  /** Conteo de ofertas vigentes (Fase 2: futuro flag del tab, roadmap Historia 3.3). */
  long countByStatusAndValidUntilAfter(OfferStatus status, OffsetDateTime cutoff);

  /** Dedup de la ingesta automática (OfferService.ingest): busca la oferta ya conocida de una fuente. */
  Optional<Offer> findByExternalKey(String externalKey);

  /** Candidatos a limpieza de cola por corrida de ingesta: todo lo scrapeado de una fuente dada. */
  List<Offer> findByOriginAndSourceName(String origin, String sourceName);

  /**
   * Incremento atómico de "Me sirve" (fase 3, señal de interacción del
   * ranking): UPDATE directo en vez de read-modify-write (mismo motivo que
   * {@code LeadPaymentRepository.markPaidIfNotAlready} — un fire-and-forget
   * concurrente sobre viewCount/clickCount ya pierde increments por esa vía,
   * likeCount no repite el problema). Devuelve cuántas filas tocó (0 si el
   * id no existe, el caller lo traduce a 404).
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query("update Offer o set o.likeCount = o.likeCount + 1 where o.id = :id")
  int incrementLikeCount(@Param("id") Long id);
}
