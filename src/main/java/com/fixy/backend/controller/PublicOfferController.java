package com.fixy.backend.controller;

import com.fixy.backend.dto.OfferInquiryCreateRequest;
import com.fixy.backend.dto.OfferPublicCountResponse;
import com.fixy.backend.dto.OfferPublicResponse;
import com.fixy.backend.service.OfferInquiryService;
import com.fixy.backend.service.OfferService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Superficie pública de lectura de Ofertas (roadmap Historia 3.3 / Loop 2,
 * SIN el tab — solo la tarjeta de cierre de pedido). Mismo patrón que
 * {@code /api/public/leads} y {@code /api/public/providers/preview}:
 * {@code permitAll} vía {@code /api/public/**} en {@code SecurityConfig},
 * sin cambios adicionales de seguridad necesarios.
 */
@RestController
@RequestMapping("/api/public/offers")
public class PublicOfferController {

  private final OfferService offerService;
  private final OfferInquiryService offerInquiryService;

  public PublicOfferController(OfferService offerService, OfferInquiryService offerInquiryService) {
    this.offerService = offerService;
    this.offerInquiryService = offerInquiryService;
  }

  @GetMapping
  public List<OfferPublicResponse> list(
      @RequestParam(required = false) String zone,
      @RequestParam(required = false) String category
  ) {
    return offerService.listPublic(zone, category);
  }

  @GetMapping("/count")
  public OfferPublicCountResponse count() {
    return new OfferPublicCountResponse(offerService.countPublic());
  }

  /** Detalle público: 200 solo si ACTIVE y vigente, 404 en cualquier otro caso. */
  @GetMapping("/{id}")
  public OfferPublicResponse get(@PathVariable Long id) {
    return offerService.getPublic(id);
  }

  /** Fire-and-forget desde el cliente: contador simple, sin idempotencia ni rate-limit. */
  @PostMapping("/{id}/view")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void view(@PathVariable Long id) {
    offerService.registerView(id);
  }

  /** Fire-and-forget desde el cliente: contador simple, sin idempotencia ni rate-limit. */
  @PostMapping("/{id}/click")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void click(@PathVariable Long id) {
    offerService.registerClick(id);
  }

  /**
   * "Me sirve" del frontend (fase 3, señal de interacción del ranking — ver
   * OfferRankingService). Mismo patrón que /view y /click: fire-and-forget,
   * sin idempotencia ni rate-limit, 404 solo si la oferta no existe.
   */
  @PostMapping("/{id}/like")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void like(@PathVariable Long id) {
    offerService.registerLike(id);
  }

  /**
   * Ruta comercio del CTA (FIXY_OFERTAS_CTA_DESIGN.md §4.3): mini-form de
   * consulta, rate-limitado + honeypot (ver OfferInquiryService). Responde
   * siempre {@code {"ok": true}}, incluso en el caso honeypot — nunca
   * expone el id interno de la inquiry ni distingue el caso bot.
   */
  @PostMapping("/{id}/inquiries")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createInquiry(
      @PathVariable Long id,
      @RequestBody OfferInquiryCreateRequest request,
      HttpServletRequest httpRequest
  ) {
    offerInquiryService.create(id, request, httpRequest.getRemoteAddr());
    return Map.of("ok", true);
  }
}
