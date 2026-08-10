package com.fixy.backend.service;

import com.fixy.backend.dto.OfferCreateRequest;
import com.fixy.backend.dto.OfferResponse;
import com.fixy.backend.dto.OfferUpdateRequest;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * CRUD admin de {@link Offer} + cola de aprobación (diseño
 * FIXY_OFERTAS_INGESTA_DESIGN.md §3.2 y §4.3). Toda oferta nace en
 * {@code DRAFT}, sea cual sea su origen — ninguna pasa a {@code ACTIVE} sin
 * la acción explícita {@link #approve}.
 */
@Service
public class OfferService {

  /** Vigencia default cuando ops aprueba sin cargar validUntil (diseño §5). */
  private static final long DEFAULT_VALIDITY_DAYS = 14;

  private final OfferRepository offerRepository;
  private final BusinessRepository businessRepository;
  private final Clock clock;

  public OfferService(OfferRepository offerRepository, BusinessRepository businessRepository, Clock clock) {
    this.offerRepository = offerRepository;
    this.businessRepository = businessRepository;
    this.clock = clock;
  }

  public List<OfferResponse> list(String status) {
    List<Offer> offers = (status == null || status.isBlank())
        ? offerRepository.findAllByOrderByCreatedAtDesc()
        : offerRepository.findByStatusOrderByCreatedAtDesc(parseStatus(status));
    return offers.stream().map(this::toResponse).toList();
  }

  private OfferStatus parseStatus(String raw) {
    try {
      return OfferStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status");
    }
  }

  public OfferResponse get(Long id) {
    return toResponse(findOffer(id));
  }

  public OfferResponse create(OfferCreateRequest request) {
    if (!businessRepository.existsById(request.businessId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "business not found");
    }
    Offer offer = new Offer();
    offer.setBusinessId(request.businessId());
    offer.setTitle(request.title().trim());
    offer.setCategory(request.category().trim());
    offer.setZone(trimToNull(request.zone()));
    offer.setDescription(trimToNull(request.description()));
    offer.setDiscountText(trimToNull(request.discountText()));
    offer.setValidFrom(request.validFrom());
    offer.setValidUntil(request.validUntil());
    offer.setPhotoUrl(trimToNull(request.photoUrl()));
    offer.setOrigin(defaultIfBlank(request.origin(), Offer.ORIGIN_MANUAL));
    offer.setSourceMessageRaw(trimToNull(request.sourceMessageRaw()));
    offer.setStatus(OfferStatus.DRAFT);
    return toResponse(offerRepository.save(offer));
  }

  public OfferResponse update(Long id, OfferUpdateRequest request) {
    Offer offer = findOffer(id);

    if (request.title() != null) offer.setTitle(request.title().trim());
    if (request.category() != null) offer.setCategory(request.category().trim());
    if (request.zone() != null) offer.setZone(trimToNull(request.zone()));
    if (request.description() != null) offer.setDescription(trimToNull(request.description()));
    if (request.discountText() != null) offer.setDiscountText(trimToNull(request.discountText()));
    if (request.validFrom() != null) offer.setValidFrom(request.validFrom());
    if (request.validUntil() != null) offer.setValidUntil(request.validUntil());
    if (request.photoUrl() != null) offer.setPhotoUrl(trimToNull(request.photoUrl()));
    if (request.sourceMessageRaw() != null) offer.setSourceMessageRaw(trimToNull(request.sourceMessageRaw()));

    return toResponse(offerRepository.save(offer));
  }

  /**
   * draft → active. Regla de negocio no negociable (diseño §5): una oferta
   * NUNCA puede quedar {@code ACTIVE} sin {@code validUntil}. Si ops no la
   * cargó, se completa automáticamente acá: 14 días desde {@code validFrom},
   * o desde el momento de la aprobación si tampoco hay {@code validFrom}.
   */
  public OfferResponse approve(Long id) {
    Offer offer = findOffer(id);
    if (offer.getStatus() != OfferStatus.DRAFT) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "solo se puede aprobar una oferta en draft (estado actual: " + offer.getStatus() + ")");
    }
    OffsetDateTime now = OffsetDateTime.now(clock);
    if (offer.getValidFrom() == null) {
      offer.setValidFrom(now);
    }
    if (offer.getValidUntil() == null) {
      OffsetDateTime base = offer.getValidFrom() != null ? offer.getValidFrom() : now;
      offer.setValidUntil(base.plusDays(DEFAULT_VALIDITY_DAYS));
    }
    offer.setStatus(OfferStatus.ACTIVE);
    return toResponse(offerRepository.save(offer));
  }

  /** draft → rejected. Sin acción sobre ofertas que ya salieron de draft. */
  public OfferResponse reject(Long id) {
    Offer offer = findOffer(id);
    if (offer.getStatus() != OfferStatus.DRAFT) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "solo se puede rechazar una oferta en draft (estado actual: " + offer.getStatus() + ")");
    }
    offer.setStatus(OfferStatus.REJECTED);
    return toResponse(offerRepository.save(offer));
  }

  private Offer findOffer(Long id) {
    return offerRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found"));
  }

  private OfferResponse toResponse(Offer offer) {
    return new OfferResponse(
        offer.getId(),
        offer.getBusinessId(),
        offer.getTitle(),
        offer.getCategory(),
        offer.getZone(),
        offer.getDescription(),
        offer.getDiscountText(),
        offer.getValidFrom(),
        offer.getValidUntil(),
        offer.getPhotoUrl(),
        offer.getStatus(),
        offer.getOrigin(),
        offer.getSourceMessageRaw(),
        offer.getCreatedAt(),
        offer.getUpdatedAt()
    );
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isBlank() ? null : trimmed;
  }

  private String defaultIfBlank(String value, String fallback) {
    String trimmed = trimToNull(value);
    return trimmed == null ? fallback : trimmed;
  }
}
