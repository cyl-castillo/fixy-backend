package com.fixy.backend.controller;

import com.fixy.backend.dto.BusinessPublicResponse;
import com.fixy.backend.service.PublicBusinessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Página pública del comercio (Fase 3 de la mutación hacia ficha, gap
 * analysis 2026-08-25 §3): {@code permitAll} vía {@code /api/public/**} en
 * {@code SecurityConfig}, sin cambios adicionales de seguridad necesarios.
 */
@RestController
@RequestMapping("/api/public/businesses")
public class PublicBusinessController {

  private final PublicBusinessService publicBusinessService;

  public PublicBusinessController(PublicBusinessService publicBusinessService) {
    this.publicBusinessService = publicBusinessService;
  }

  @GetMapping("/{slug}")
  public BusinessPublicResponse get(@PathVariable String slug, HttpServletRequest httpRequest) {
    return publicBusinessService.getBySlug(httpRequest.getRemoteAddr(), slug);
  }
}
