package com.fixy.backend.controller;

import com.fixy.backend.service.BarrioOgHtmlService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Preview OG de {@code /barrio/r/{id}} para bots de WhatsApp/Facebook. SIEMPRE 200 (ver BarrioOgHtmlService). */
@RestController
public class BarrioOgController {

  private final BarrioOgHtmlService html;

  public BarrioOgController(BarrioOgHtmlService html) {
    this.html = html;
  }

  @GetMapping("/og/barrio/r/{id}")
  public ResponseEntity<String> barrioOg(@PathVariable Long id) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
        .body(html.render(id));
  }
}
