package com.fixy.backend.service;

import com.fixy.backend.dto.MerchantOfferSummary;
import com.fixy.backend.dto.MerchantPanelResponse;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.OfferInquiryRepository;
import com.fixy.backend.repository.OfferRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Panel self-service del comercio (Fase 5 del roadmap "Fixy referencia de
 * ofertas", §5): el dueño entra por link con token — SIN password — a ver
 * TODAS sus ofertas con métricas reales, y a renovar/pausar sin pasar por
 * ops. El token viaja en el path de cada ruta pública
 * ({@code /api/public/merchant/{token}/...}); ver {@code BusinessService}
 * para cómo se genera.
 *
 * <p>Contrato de errores, deliberado: un token que no resuelve a ningún
 * comercio SIEMPRE es 404 opaco — mismo mensaje sin importar si el token
 * nunca existió o si existe pero apunta a un comercio inactivo, y lo mismo
 * para una oferta que no pertenece al comercio del token. No hay forma de
 * que un request externo distinga esos casos entre sí.
 */
@Service
public class MerchantPanelService {

  private static final Set<Integer> ALLOWED_RENEW_WEEKS = Set.of(1, 2, 4);

  /** Orden de la lista del panel: ACTIVE primero (lo que importa hoy), luego
   * DRAFT (pendiente de moderación), EXPIRED, REJECTED al final. */
  private static final Map<OfferStatus, Integer> STATUS_ORDER = Map.of(
      OfferStatus.ACTIVE, 0,
      OfferStatus.DRAFT, 1,
      OfferStatus.EXPIRED, 2,
      OfferStatus.REJECTED, 3
  );

  private static final Comparator<Offer> PANEL_ORDER = Comparator
      .comparing((Offer o) -> STATUS_ORDER.getOrDefault(o.getStatus(), 4))
      .thenComparing(Offer::getValidUntil, Comparator.nullsLast(Comparator.reverseOrder()));

  private final BusinessRepository businessRepository;
  private final OfferRepository offerRepository;
  private final LeadRepository leadRepository;
  private final OfferInquiryRepository offerInquiryRepository;
  private final PublicLeadAbuseProtectionService abuseProtectionService;
  private final TelegramNotifyService telegramNotifyService;
  private final Clock clock;

  public MerchantPanelService(
      BusinessRepository businessRepository,
      OfferRepository offerRepository,
      LeadRepository leadRepository,
      OfferInquiryRepository offerInquiryRepository,
      PublicLeadAbuseProtectionService abuseProtectionService,
      TelegramNotifyService telegramNotifyService,
      Clock clock
  ) {
    this.businessRepository = businessRepository;
    this.offerRepository = offerRepository;
    this.leadRepository = leadRepository;
    this.offerInquiryRepository = offerInquiryRepository;
    this.abuseProtectionService = abuseProtectionService;
    this.telegramNotifyService = telegramNotifyService;
    this.clock = clock;
  }

  /** Todas las ofertas del comercio (cualquier estado), métricas reales sin umbral de social proof. */
  public MerchantPanelResponse getPanel(String clientIp, String token) {
    abuseProtectionService.validateMerchantPanel(clientIp);
    Business business = requireBusiness(token);

    List<MerchantOfferSummary> offers = offerRepository.findByBusinessIdOrderByCreatedAtDesc(business.getId())
        .stream()
        .sorted(PANEL_ORDER)
        .map(this::toSummary)
        .toList();

    return new MerchantPanelResponse(
        new MerchantPanelResponse.BusinessSummary(
            business.getId(), business.getName(), business.getCategory(), business.getPrimaryZone()),
        offers
    );
  }

  /**
   * ACTIVE: extiende {@code validUntil} desde {@code max(ahora, validUntil
   * actual)} — igual criterio que {@code OfferService.extendValidity}, nunca
   * recorta lo que ya tenía. EXPIRED: vuelve a {@code DRAFT} con vigencia
   * nueva desde ahora — la re-publicación pasa por moderación igual que
   * cualquier otro origen, así que se avisa a ops. DRAFT/REJECTED: 409, el
   * dueño no tiene nada que "renovar" ahí (una todavía sin aprobar, la otra
   * ya descartada por un humano a propósito).
   */
  public MerchantOfferSummary renew(String clientIp, String token, Long offerId, Integer weeks) {
    abuseProtectionService.validateMerchantPanel(clientIp);
    if (weeks == null || !ALLOWED_RENEW_WEEKS.contains(weeks)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "weeks must be 1, 2 or 4");
    }
    Business business = requireBusiness(token);
    Offer offer = requireOwnOffer(business, offerId);
    OffsetDateTime now = OffsetDateTime.now(clock);

    switch (offer.getStatus()) {
      case ACTIVE -> {
        OffsetDateTime base = (offer.getValidUntil() == null || offer.getValidUntil().isBefore(now))
            ? now : offer.getValidUntil();
        offer.setValidUntil(base.plusDays(weeks * 7L));
      }
      case EXPIRED -> {
        offer.setStatus(OfferStatus.DRAFT);
        offer.setValidFrom(now);
        offer.setValidUntil(now.plusDays(weeks * 7L));
        try {
          telegramNotifyService.notifyMerchantOfferRenewal(business, offer);
        } catch (Exception ex) {
          // best-effort: un aviso a ops que falla no debe romper la renovación del dueño
        }
      }
      default -> throw new ResponseStatusException(HttpStatus.CONFLICT,
          "solo se puede renovar una oferta activa o vencida (estado actual: " + offer.getStatus() + ")");
    }

    return toSummary(offerRepository.save(offer));
  }

  /**
   * Solo ACTIVE → EXPIRED, ya (no espera a {@link OfferExpirationScheduler}):
   * {@code validUntil} pasa a ahora y el status se marca EXPIRED en el mismo
   * request. Decisión deliberada sobre la alternativa de solo tocar {@code
   * validUntil} y dejar que el scheduler la marque en su próxima pasada
   * (hasta 1h de delay, ver {@code fixy.offers.expiration.scheduler-fixed-delay-ms}):
   * el dueño que pausa espera ver el cambio reflejado YA en su propio panel
   * y en la superficie pública, sin un estado transitorio "ACTIVE pero
   * vencida" en el medio. Cualquier otro estado: 409.
   */
  public MerchantOfferSummary pause(String clientIp, String token, Long offerId) {
    abuseProtectionService.validateMerchantPanel(clientIp);
    Business business = requireBusiness(token);
    Offer offer = requireOwnOffer(business, offerId);
    if (offer.getStatus() != OfferStatus.ACTIVE) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "solo se puede pausar una oferta activa (estado actual: " + offer.getStatus() + ")");
    }
    offer.setValidUntil(OffsetDateTime.now(clock));
    offer.setStatus(OfferStatus.EXPIRED);
    return toSummary(offerRepository.save(offer));
  }

  private Business requireBusiness(String token) {
    if (token == null || token.isBlank()) {
      throw opaqueNotFound();
    }
    return businessRepository.findByPanelToken(token).orElseThrow(this::opaqueNotFound);
  }

  /** 404 opaco también si la oferta existe pero es de OTRO comercio — nunca revela que existe. */
  private Offer requireOwnOffer(Business business, Long offerId) {
    Offer offer = offerRepository.findById(offerId).orElseThrow(this::opaqueNotFound);
    if (!business.getId().equals(offer.getBusinessId())) {
      throw opaqueNotFound();
    }
    return offer;
  }

  private ResponseStatusException opaqueNotFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
  }

  private MerchantOfferSummary toSummary(Offer offer) {
    return new MerchantOfferSummary(
        offer.getId(),
        offer.getTitle(),
        offer.getDiscountText(),
        offer.getStatus(),
        offer.getValidFrom(),
        offer.getValidUntil(),
        offer.getPhotoUrl(),
        offer.getViewCount(),
        offer.getClickCount(),
        offer.getLikeCount(),
        offerInquiryRepository.countByOfferId(offer.getId()),
        (int) leadRepository.countBySourceOfferId(offer.getId())
    );
  }
}
