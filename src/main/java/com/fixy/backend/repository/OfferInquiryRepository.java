package com.fixy.backend.repository;

import com.fixy.backend.model.OfferInquiry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfferInquiryRepository extends JpaRepository<OfferInquiry, Long> {
  List<OfferInquiry> findByOfferIdOrderByCreatedAtDesc(Long offerId);

  /** OfferResponse.inquiryCount — ver OfferService.toResponse. */
  int countByOfferId(Long offerId);
}
