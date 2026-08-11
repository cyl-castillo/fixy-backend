package com.fixy.backend.repository;

import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
