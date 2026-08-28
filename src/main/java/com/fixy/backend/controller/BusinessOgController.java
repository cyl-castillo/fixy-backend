package com.fixy.backend.controller;

import com.fixy.backend.service.BusinessOgHtmlService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint público para bots de preview de links (WhatsApp/Meta no ejecutan
 * JS) y para crawlers de buscadores — mismo patrón que {@code
 * OfferOgController}. Sirve el shell de la SPA con meta tags Open Graph +
 * JSON-LD {@code LocalBusiness} inyectados con los datos reales del comercio
 * cuando existe y está {@code ACTIVE}. Contrato: SIEMPRE 200 — si el
 * comercio no existe/no está activo/no tiene slug, se sirve el index.html
 * genérico intacto. Público vía {@code /og/**} en {@code SecurityConfig}.
 */
@RestController
public class BusinessOgController {

  private final BusinessOgHtmlService businessOgHtmlService;

  public BusinessOgController(BusinessOgHtmlService businessOgHtmlService) {
    this.businessOgHtmlService = businessOgHtmlService;
  }

  @GetMapping("/og/comercio/{slug}")
  public ResponseEntity<String> comercioOg(@PathVariable String slug) {
    String html = businessOgHtmlService.render(slug);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
        .body(html);
  }
}
