package com.fixy.backend.service;

import com.fixy.backend.dto.BusinessPublicResponse;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessCatalogItem;
import com.fixy.backend.model.BusinessHour;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessCatalogItemRepository;
import com.fixy.backend.repository.BusinessHourRepository;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/public/businesses/{slug}} (Fase 3 de la mutación hacia
 * ficha, gap analysis 2026-08-25 §3): la página pública del comercio.
 * Contrato de errores, deliberado — mismo criterio que {@code
 * MerchantPanelService}: cualquier slug que no resuelve a un comercio
 * {@code ACTIVE} es 404 opaco, sin distinguir "no existe" de "existe pero
 * inactivo/sin slug todavía".
 */
@Service
public class PublicBusinessService {

  private final BusinessRepository businessRepository;
  private final BusinessHourRepository businessHourRepository;
  private final BusinessCatalogItemRepository businessCatalogItemRepository;
  private final OfferRepository offerRepository;
  private final PublicLeadAbuseProtectionService abuseProtectionService;
  private final Clock clock;
  private final int socialProofMinViews;

  public PublicBusinessService(
      BusinessRepository businessRepository,
      BusinessHourRepository businessHourRepository,
      BusinessCatalogItemRepository businessCatalogItemRepository,
      OfferRepository offerRepository,
      PublicLeadAbuseProtectionService abuseProtectionService,
      Clock clock,
      // MISMO umbral que OfferPublicResponse.viewCount (OfferService) —
      // política única de social proof en todo lo público, no dos números
      // distintos según sea oferta o comercio.
      @Value("${fixy.offers.social-proof-min-views:10}") int socialProofMinViews
  ) {
    this.businessRepository = businessRepository;
    this.businessHourRepository = businessHourRepository;
    this.businessCatalogItemRepository = businessCatalogItemRepository;
    this.offerRepository = offerRepository;
    this.abuseProtectionService = abuseProtectionService;
    this.clock = clock;
    this.socialProofMinViews = socialProofMinViews;
  }

  public BusinessPublicResponse getBySlug(String clientIp, String slug) {
    abuseProtectionService.validatePublicBusinessPage(clientIp);
    Business business = requireVisibleBusiness(slug);

    List<BusinessHour> hours = businessHourRepository.findByBusinessIdOrderByDayOfWeekAscOpensAtAsc(business.getId());
    List<BusinessCatalogItem> catalogItems = businessCatalogItemRepository.findByBusinessIdAndActiveTrue(business.getId());
    OffsetDateTime now = OffsetDateTime.now(clock);
    List<Offer> vigentOffers = offerRepository.findByBusinessIdOrderByCreatedAtDesc(business.getId()).stream()
        .filter(offer -> offer.getStatus() == OfferStatus.ACTIVE
            && offer.getValidUntil() != null && offer.getValidUntil().isAfter(now))
        .toList();

    BusinessPublicResponse response = toResponse(business, hours, catalogItems, vigentOffers);
    incrementViewCountBestEffort(business.getId());
    return response;
  }

  private Business requireVisibleBusiness(String slug) {
    if (slug == null || slug.isBlank()) {
      throw opaqueNotFound();
    }
    Business business = businessRepository.findBySlug(slug).orElseThrow(this::opaqueNotFound);
    if (business.getStatus() != BusinessStatus.ACTIVE) {
      throw opaqueNotFound();
    }
    return business;
  }

  /** Fire-and-forget: un fallo acá nunca puede tirar abajo la respuesta pública. */
  private void incrementViewCountBestEffort(Long businessId) {
    try {
      businessRepository.incrementViewCount(businessId);
    } catch (Exception ex) {
      // best-effort, ver javadoc del método.
    }
  }

  private ResponseStatusException opaqueNotFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "business not found");
  }

  private BusinessPublicResponse toResponse(
      Business business, List<BusinessHour> hours, List<BusinessCatalogItem> catalogItems, List<Offer> offers
  ) {
    return new BusinessPublicResponse(
        business.getId(),
        business.getSlug(),
        business.getName(),
        business.getCategory(),
        business.getCategories(),
        business.getPrimaryZone(),
        business.getAddress(),
        business.getLatitude(),
        business.getLongitude(),
        business.getDescription(),
        hours.stream()
            .map(hour -> new BusinessPublicResponse.Hour(
                hour.getDayOfWeek(), hour.getOpensAt(), hour.getClosesAt(), hour.getNote()))
            .toList(),
        catalogItems.stream()
            .map(item -> new BusinessPublicResponse.CatalogItem(
                item.getId(), item.getLabel(), item.getKind().name(), item.getPriceFrom(),
                item.getConfidence().name(), item.getVerifiedAt(), item.isAvailable()))
            .toList(),
        offers.stream()
            .map(offer -> new BusinessPublicResponse.OfferSummary(
                offer.getId(), offer.getTitle(), offer.getDiscountText(), offer.getValidUntil(), offer.getPhotoUrl()))
            .toList(),
        business.getViewCount() >= socialProofMinViews ? business.getViewCount() : null
    );
  }
}
