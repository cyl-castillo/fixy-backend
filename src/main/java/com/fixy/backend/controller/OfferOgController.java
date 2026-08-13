package com.fixy.backend.controller;

import com.fixy.backend.service.OfferOgHtmlService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint público para bots de preview de links (WhatsApp/Meta no ejecutan
 * JS). Sirve el shell de la SPA con meta tags Open Graph inyectados con los
 * datos reales de la oferta cuando existe y está vigente. Contrato: SIEMPRE
 * 200 — si la oferta no existe/no está vigente, se sirve el index.html
 * genérico intacto (la SPA ya resuelve "ya no vigente" del lado cliente).
 * Público vía {@code /og/**} en {@code SecurityConfig}.
 */
@RestController
public class OfferOgController {

  private final OfferOgHtmlService offerOgHtmlService;

  public OfferOgController(OfferOgHtmlService offerOgHtmlService) {
    this.offerOgHtmlService = offerOgHtmlService;
  }

  @GetMapping("/og/oferta/{id}")
  public ResponseEntity<String> ofertaOg(@PathVariable Long id) {
    String html = offerOgHtmlService.render(id);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
        .body(html);
  }
}
