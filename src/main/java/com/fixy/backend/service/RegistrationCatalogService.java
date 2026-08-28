package com.fixy.backend.service;

import com.fixy.backend.dto.RegistrationCatalogResponse;
import com.fixy.backend.dto.RegistrationCategoryOption;
import com.fixy.backend.model.BusinessCategory;
import com.fixy.backend.model.ServiceCategory;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Catálogo único de rubros para la puerta de registro pública (Fase 1+2
 * "puerta única de registro", Carlos 2026-08-27): un solo GET que le da al
 * wizard de alta las dos listas que necesita — oficios de PROVEEDOR ({@link
 * ServiceCategory}, mismo filtro sin "otro" que ya aplica {@link
 * ProviderRegistrationService#validateCategories}) y rubros de COMERCIO
 * ({@link BusinessCategory}, catálogo nuevo, con "otro" incluido). Ambas
 * listas son estáticas (no cambian en runtime) — se computan una sola vez.
 */
@Service
public class RegistrationCatalogService {

  private static final List<RegistrationCategoryOption> PROVIDER_OPTIONS = Arrays.stream(ServiceCategory.values())
      .filter(category -> category != ServiceCategory.OTRO)
      .map(category -> new RegistrationCategoryOption(category.id(), category.label()))
      .toList();

  private static final List<RegistrationCategoryOption> BUSINESS_OPTIONS = Arrays.stream(BusinessCategory.values())
      .map(category -> new RegistrationCategoryOption(category.id(), category.label()))
      .toList();

  public RegistrationCatalogResponse get() {
    return new RegistrationCatalogResponse(PROVIDER_OPTIONS, BUSINESS_OPTIONS);
  }
}
