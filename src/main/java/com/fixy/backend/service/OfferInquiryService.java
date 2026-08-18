package com.fixy.backend.service;

import com.fixy.backend.dto.OfferInquiryCreateRequest;
import com.fixy.backend.dto.OfferInquiryResponse;
import com.fixy.backend.dto.OfferInquiryStatusUpdateRequest;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferInquiry;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferInquiryRepository;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ruta comercio del CTA de ofertas (FIXY_OFERTAS_CTA_DESIGN.md §4): mini-form
 * determinista de consulta a un comercio real. Cola propia
 * ({@link OfferInquiry}), separada de {@link com.fixy.backend.model.Lead} —
 * semántica distinta (§4.1): un mensaje de contacto completo desde el
 * momento en que se manda, no un pedido de servicio con datos faltantes.
 */
@Service
public class OfferInquiryService {

  private static final Logger log = LoggerFactory.getLogger(OfferInquiryService.class);

  private final OfferInquiryRepository offerInquiryRepository;
  private final BusinessRepository businessRepository;
  private final OfferService offerService;
  private final PublicLeadAbuseProtectionService abuseProtectionService;
  private final TelegramNotifyService telegramNotifyService;

  public OfferInquiryService(
      OfferInquiryRepository offerInquiryRepository,
      BusinessRepository businessRepository,
      OfferService offerService,
      PublicLeadAbuseProtectionService abuseProtectionService,
      TelegramNotifyService telegramNotifyService
  ) {
    this.offerInquiryRepository = offerInquiryRepository;
    this.businessRepository = businessRepository;
    this.offerService = offerService;
    this.abuseProtectionService = abuseProtectionService;
    this.telegramNotifyService = telegramNotifyService;
  }

  /**
   * Crea una consulta pública (§4.3/§6). El honeypot se chequea PRIMERO,
   * antes de cualquier otra validación — incluida la del {@code Offer}: un
   * bot que completa {@code website} recibe éxito igual sin filtrar ni
   * siquiera si el id de oferta existe (nunca delatar al bot). Sin retorno:
   * el controller siempre responde {@code 201 {"ok": true}} sin distinguir
   * el caso honeypot del caso real — ese es justamente el punto.
   */
  public void create(Long offerId, OfferInquiryCreateRequest request, String clientIp) {
    if (hasText(request.website())) {
      return;
    }

    Offer offer = offerService.findActiveOfferForInquiry(offerId);
    abuseProtectionService.validateOfferInquiry(clientIp, request.name(), request.whatsappNumber(), request.message());

    Business business = businessRepository.findById(offer.getBusinessId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "offer does not accept inquiries"));

    OfferInquiry inquiry = new OfferInquiry();
    inquiry.setOfferId(offer.getId());
    inquiry.setBusinessId(business.getId());
    inquiry.setName(request.name().trim());
    inquiry.setWhatsappNumber(request.whatsappNumber().trim());
    inquiry.setMessage(request.message().trim());
    OfferInquiry saved = offerInquiryRepository.save(inquiry);

    try {
      telegramNotifyService.notifyOfferInquiry(offer, business, saved);
    } catch (Exception ex) {
      // best-effort, nunca romper el endpoint público
      log.warn("telegram notify offer-inquiry id={} failed: {}", saved.getId(), ex.getMessage());
    }
  }

  /** Drill-down admin de consultas por oferta (§4.3). */
  public List<OfferInquiryResponse> listForOffer(Long offerId) {
    return offerInquiryRepository.findByOfferIdOrderByCreatedAtDesc(offerId).stream()
        .map(this::toResponse)
        .toList();
  }

  /**
   * Único estado que ops puede setear a mano: FORWARDED (ya reenvió la
   * consulta al comercio por WhatsApp). No hay reapertura ni otros estados
   * — dos estados alcanzan al volumen de Fase 1 (§4.2).
   */
  public OfferInquiryResponse updateStatus(Long offerId, Long inquiryId, OfferInquiryStatusUpdateRequest request) {
    OfferInquiry inquiry = offerInquiryRepository.findById(inquiryId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "inquiry not found"));
    if (!inquiry.getOfferId().equals(offerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "inquiry not found");
    }
    String status = request.status() == null ? "" : request.status().trim().toUpperCase(Locale.ROOT);
    if (!OfferInquiry.STATUS_FORWARDED.equals(status)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported status");
    }
    inquiry.setStatus(OfferInquiry.STATUS_FORWARDED);
    return toResponse(offerInquiryRepository.save(inquiry));
  }

  private OfferInquiryResponse toResponse(OfferInquiry inquiry) {
    return new OfferInquiryResponse(
        inquiry.getId(),
        inquiry.getOfferId(),
        inquiry.getBusinessId(),
        inquiry.getName(),
        inquiry.getWhatsappNumber(),
        inquiry.getMessage(),
        inquiry.getStatus(),
        inquiry.getCreatedAt(),
        inquiry.getUpdatedAt()
    );
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isBlank();
  }
}
