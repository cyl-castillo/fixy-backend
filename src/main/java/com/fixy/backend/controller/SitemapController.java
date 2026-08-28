package com.fixy.backend.controller;

import com.fixy.backend.service.SitemapService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /sitemap.xml}: SEO básico para que buscadores indexen home,
 * {@code /ofertas} y cada oferta pública vigente. Público vía
 * {@code /sitemap.xml} en {@code SecurityConfig} (mismo patrón que
 * {@code /og/**}). Armado del XML en {@link SitemapService}.
 */
@RestController
public class SitemapController {

  private final SitemapService sitemapService;

  public SitemapController(SitemapService sitemapService) {
    this.sitemapService = sitemapService;
  }

  @GetMapping(value = "/sitemap.xml", produces = "application/xml")
  public ResponseEntity<String> sitemap() {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "application/xml;charset=UTF-8")
        .body(sitemapService.render());
  }
}
