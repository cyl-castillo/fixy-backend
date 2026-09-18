package com.fixy.backend.repository;

import com.fixy.backend.model.ProviderOffer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderOfferRepository extends JpaRepository<ProviderOffer, Long> {

  /**
   * La oferta abierta del par (lead, proveedor) — la última con
   * {@code respondedAt} nulo (contrato §A.2). Puede haber más de una fila
   * histórica cerrada para el mismo par (declinó, se re-ofreció otra vez
   * más tarde); solo la más reciente sin responder es "la abierta".
   */
  Optional<ProviderOffer> findFirstByLeadIdAndProviderIdAndRespondedAtIsNullOrderByOfferedAtDesc(
      Long leadId, Long providerId);

  /** Últimas N ofertas EN VENTANA del proveedor, para el score de respuesta
   * (§A.3) — se limita en el servicio a {@code sample-size} (default 10). */
  List<ProviderOffer> findByProviderIdAndInWindowTrueOrderByOfferedAtDesc(Long providerId);

  /** Todo el historial de ofertas de un par (lead, proveedor), abiertas o
   * cerradas — usado por tests para verificar cómo se cerró una oferta. */
  List<ProviderOffer> findByLeadIdAndProviderIdOrderByOfferedAtDesc(Long leadId, Long providerId);
}
