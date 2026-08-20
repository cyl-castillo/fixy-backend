package com.fixy.backend.service;

import com.fixy.backend.dto.PublicOfferSubmissionRequest;
import com.fixy.backend.dto.PublicOfferSubmissionResponse;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Alta pública de ofertas (fase 2 del roadmap "ofertas protagonistas",
 * FIXY_OFERTAS_INGESTA_DESIGN.md): hasta ahora la única puerta de carga era
 * el admin de ops (Carlos en el mostrador del comercio) — el cuello de
 * botella #1. Esta ruta deja que el comerciante la cargue solo desde su
 * celular. El pipeline de estados NO cambia: la oferta nace siempre
 * {@code DRAFT} (mismo {@code Offer.prePersist} que cualquier otro origen) y
 * solo ops la pasa a {@code ACTIVE} ({@link OfferService#approve}).
 *
 * <p>Mismo esqueleto que {@link OfferInquiryService}: honeypot primero
 * (nunca delatar al bot, ni siquiera con una validación distinta), después
 * validación + rate limit ({@link PublicLeadAbuseProtectionService}), y
 * aviso a ops por Telegram best-effort al final.
 */
@Service
public class PublicOfferSubmissionService {

  private static final Logger log = LoggerFactory.getLogger(PublicOfferSubmissionService.class);

  /** Placeholder de la respuesta cuando la request es el honeypot: nunca se
   * persiste nada, pero la forma de la respuesta tiene que ser indistinguible
   * de una alta real para no delatar al bot. */
  private static final PublicOfferSubmissionResponse HONEYPOT_RESPONSE =
      new PublicOfferSubmissionResponse(0L, 0L, OfferStatus.DRAFT.name());

  private final BusinessRepository businessRepository;
  private final OfferRepository offerRepository;
  private final PublicLeadAbuseProtectionService abuseProtectionService;
  private final TelegramNotifyService telegramNotifyService;

  public PublicOfferSubmissionService(
      BusinessRepository businessRepository,
      OfferRepository offerRepository,
      PublicLeadAbuseProtectionService abuseProtectionService,
      TelegramNotifyService telegramNotifyService
  ) {
    this.businessRepository = businessRepository;
    this.offerRepository = offerRepository;
    this.abuseProtectionService = abuseProtectionService;
    this.telegramNotifyService = telegramNotifyService;
  }

  /**
   * Crea (o reusa) el {@link Business} del comerciante y su primera
   * {@link Offer} en {@code DRAFT}. El honeypot se chequea PRIMERO, antes de
   * cualquier otra validación — un bot que completa {@code website} recibe
   * 201 igual sin persistir nada (ver {@link #HONEYPOT_RESPONSE}).
   */
  public PublicOfferSubmissionResponse create(PublicOfferSubmissionRequest request, String clientIp) {
    if (hasText(request.website())) {
      return HONEYPOT_RESPONSE;
    }

    abuseProtectionService.validateOfferSubmission(
        clientIp,
        request.businessName(),
        request.whatsappNumber(),
        request.category(),
        request.zone(),
        request.title(),
        request.address(),
        request.discountText(),
        request.description()
    );

    Business business = findOrCreateBusiness(request);

    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle(request.title().trim());
    offer.setCategory(request.category().trim());
    offer.setZone(trimToNull(request.zone()));
    offer.setDescription(trimToNull(request.description()));
    offer.setDiscountText(trimToNull(request.discountText()));
    offer.setValidUntil(request.validUntil());
    offer.setOrigin(Offer.ORIGIN_MANUAL);
    Offer saved = offerRepository.save(offer);

    try {
      telegramNotifyService.notifyOfferSubmission(business, saved);
    } catch (Exception ex) {
      // best-effort, nunca romper el endpoint público
      log.warn("telegram notify offer-submission offerId={} failed: {}", saved.getId(), ex.getMessage());
    }

    return new PublicOfferSubmissionResponse(saved.getId(), business.getId(), saved.getStatus().name());
  }

  /**
   * Mismo número de WhatsApp normalizado ({@link BusinessRepository#findByWhatsappNumber},
   * mismo criterio que {@code ProviderRegistrationService}: solo dígitos, sin
   * espacios/+) → reusa el comercio existente y le refresca address/lat/lng
   * si llegaron nuevos (nunca pisa nombre/categoría/zona, esos ya los cargó
   * el comerciante o ops antes). Si no existe, lo crea.
   */
  private Business findOrCreateBusiness(PublicOfferSubmissionRequest request) {
    String normalizedPhone = request.whatsappNumber().replaceAll("\\D", "");
    Business business = businessRepository.findByWhatsappNumber(normalizedPhone).orElse(null);

    if (business != null) {
      if (hasText(request.address())) {
        business.setAddress(request.address().trim());
      }
      if (request.latitude() != null) {
        business.setLatitude(request.latitude());
      }
      if (request.longitude() != null) {
        business.setLongitude(request.longitude());
      }
      return businessRepository.save(business);
    }

    Business created = new Business();
    created.setName(request.businessName().trim());
    created.setWhatsappNumber(request.whatsappNumber().trim());
    created.setCategory(request.category().trim());
    created.setPrimaryZone(trimToNull(request.zone()));
    created.setAddress(trimToNull(request.address()));
    created.setLatitude(request.latitude());
    created.setLongitude(request.longitude());
    created.setStatus(BusinessStatus.ACTIVE);
    return businessRepository.save(created);
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isBlank() ? null : trimmed;
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isBlank();
  }
}
