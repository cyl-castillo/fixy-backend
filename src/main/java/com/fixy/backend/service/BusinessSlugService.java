package com.fixy.backend.service;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.CoverageZone;
import com.fixy.backend.repository.BusinessRepository;
import org.springframework.stereotype.Service;

/**
 * Genera el {@code slug} de la página pública del comercio (Fase 3 de la
 * mutación hacia ficha, gap analysis 2026-08-25 §3), mismo patrón lazy e
 * idempotente que {@code BusinessService.ensurePanelLink}: se llama desde
 * el alta de un {@link Business} nuevo y desde {@code POST
 * /api/businesses/{id}/public-link}, nunca desde una lectura pública (un
 * GET no puede tener efectos secundarios de escritura que decidan una URL
 * permanente).
 *
 * <p><b>Nunca regenera un slug ya asignado</b>: las URLs públicas
 * compartidas (WhatsApp, redes, Google) no pueden cambiar por debajo del
 * comercio.
 */
@Service
public class BusinessSlugService {

  private static final int MAX_SLUG_LENGTH = 60;
  private static final String FALLBACK_BASE = "comercio";

  private final BusinessRepository businessRepository;

  public BusinessSlugService(BusinessRepository businessRepository) {
    this.businessRepository = businessRepository;
  }

  /**
   * Idempotente: si {@code business} ya tiene slug, lo devuelve tal cual sin
   * tocar nada. Si no, normaliza el nombre (minúsculas, sin acentos —
   * {@link CoverageZone#normalize(String)}, mismo criterio que el resto del
   * repo), reemplaza todo lo no-alfanumérico por guiones, colapsa guiones
   * consecutivos y recorta a {@value #MAX_SLUG_LENGTH} caracteres. Si el
   * candidato ya existe (otro comercio con nombre equivalente), le agrega
   * el sufijo {@code -{id}} — determinístico y único porque el id ya es
   * único. Requiere que {@code business} ya esté persistido (id asignado).
   */
  public String ensureSlug(Business business) {
    if (business.getSlug() != null && !business.getSlug().isBlank()) {
      return business.getSlug();
    }

    String base = baseSlug(business.getName());
    String candidate = base;
    if (businessRepository.existsBySlug(candidate)) {
      String suffix = "-" + business.getId();
      candidate = truncate(base, MAX_SLUG_LENGTH - suffix.length()) + suffix;
    }

    business.setSlug(candidate);
    businessRepository.save(business);
    return candidate;
  }

  private String baseSlug(String name) {
    String normalized = CoverageZone.normalize(name);
    String slug = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (slug.isBlank()) {
      slug = FALLBACK_BASE;
    }
    return truncate(slug, MAX_SLUG_LENGTH);
  }

  private String truncate(String value, int maxLength) {
    if (value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength).replaceAll("-+$", "");
  }
}
