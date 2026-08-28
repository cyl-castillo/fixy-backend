package com.fixy.backend.repository;

import com.fixy.backend.model.OfferInquiry;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OfferInquiryRepository extends JpaRepository<OfferInquiry, Long> {
  List<OfferInquiry> findByOfferIdOrderByCreatedAtDesc(Long offerId);

  /** OfferResponse.inquiryCount — ver OfferService.toResponse. */
  int countByOfferId(Long offerId);

  /**
   * Conteo agrupado por oferta — usado por {@code OfferService.listPublic}
   * (fase 3, señal de interacción del ranking: {@code OfferRankingService}
   * necesita el inquiryCount de cada oferta del listado) para evitar un N+1
   * de {@link #countByOfferId} por oferta. Cada fila es {@code [offerId,
   * count]}; ofertas sin ninguna inquiry simplemente no aparecen — el
   * caller arma el mapa con default 0.
   */
  @Query("select i.offerId, count(i) from OfferInquiry i where i.offerId in :offerIds group by i.offerId")
  List<Object[]> countGroupedByOfferId(@Param("offerIds") Collection<Long> offerIds);
}
