package com.fixy.backend.service;

import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.model.ProviderVerificationStatus;
import com.fixy.backend.model.ServiceCategory;
import com.fixy.backend.repository.ProviderRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Autoregistro de proveedores con aprobación (Carlos 2026-07-22): el
 * interesado se registra en /panel CON Google desde el arranque (la cuenta
 * ancla su identidad — nunca existe link mágico que compartir) + un form
 * corto. Nace {@code INACTIVE}: excluido del matching y de la bandeja de
 * oportunidades hasta que Carlos lo apruebe en el admin (botón "Activar" →
 * AVAILABLE, ya existente). Human-in-the-loop intacto: baja la fricción de
 * captación, no el filtro de confianza.
 */
@Service
public class ProviderRegistrationService {

  private static final int NAME_MAX = 120;
  private static final int ZONE_MAX = 80;
  private static final int COVERAGE_MAX = 1000;

  private final GoogleIdTokenVerifierService verifier;
  private final ProviderRepository providerRepository;
  private final TelegramNotifyService telegramNotifyService;
  private final PublicLeadAbuseProtectionService abuseProtectionService;

  public ProviderRegistrationService(
      GoogleIdTokenVerifierService verifier,
      ProviderRepository providerRepository,
      TelegramNotifyService telegramNotifyService,
      PublicLeadAbuseProtectionService abuseProtectionService
  ) {
    this.verifier = verifier;
    this.providerRepository = providerRepository;
    this.telegramNotifyService = telegramNotifyService;
    this.abuseProtectionService = abuseProtectionService;
  }

  public Provider register(
      String credential,
      String name,
      String phone,
      List<String> categories,
      String primaryZone,
      String coverageZones,
      String clientIp
  ) {
    // Rate limit (Fase 1+2 "puerta única de registro", 2026-08-27): este
    // endpoint no tenía ningún freno de abuso hasta ahora — se agrega acá,
    // primero, antes de gastar una verificación contra Google.
    abuseProtectionService.validateProviderRegistration(clientIp);

    GoogleIdTokenVerifierService.GoogleIdentity identity = verifier.verify(requireText(credential, "credential"))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "credential de Google inválido"));

    if (providerRepository.findByGoogleSub(identity.sub()).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "esa cuenta de Google ya tiene un proveedor en Fixy — entrá con Google desde /panel");
    }

    String cleanName = validateLength(requireText(name, "nombre"), NAME_MAX, "nombre");
    String cleanZone = validateLength(requireText(primaryZone, "zona"), ZONE_MAX, "zona");
    String cleanPhone = validatePhone(phone);
    String cleanCategories = validateCategories(categories);
    String cleanCoverage = coverageZones == null || coverageZones.isBlank()
        ? null
        : validateLength(coverageZones.trim(), COVERAGE_MAX, "zonas de cobertura");

    String normalizedPhone = cleanPhone.replaceAll("\\D", "");
    Provider existing = providerRepository.findByContactNumber(normalizedPhone).orElse(null);
    if (existing != null) {
      // Casi siempre es un proveedor real que perdió su link: aviso proactivo
      // a ops (con throttle — ver TelegramNotifyService) antes del rechazo.
      telegramNotifyService.notifyExistingProviderRegistrationAttempt(existing, identity.email());
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "ese teléfono ya está registrado en Fixy — si es tuyo, escribinos por WhatsApp");
    }

    Provider provider = new Provider();
    provider.setName(cleanName);
    provider.setPhone(cleanPhone);
    provider.setPrimaryZone(cleanZone);
    provider.setCoverageZones(cleanCoverage);
    provider.setCity("Ciudad de la Costa");
    provider.setCategories(cleanCategories);
    provider.setSourceType("autoregistro");
    provider.setSourceName("panel-web");
    // INACTIVE = fuera del matching (findMatches) y de la bandeja
    // (ProviderOpportunityService.listFor) hasta la aprobación manual.
    provider.setStatus(ProviderStatus.INACTIVE);
    provider.setVerificationStatus(ProviderVerificationStatus.UNVERIFIED);
    provider.setAcceptingWork(true);
    provider.setGoogleSub(identity.sub());
    provider.setGoogleEmail(identity.email());
    // Token del panel desde el nacimiento: el registro devuelve las
    // credenciales y el proveedor entra directo (con banner de revisión).
    provider.setAccessToken(UUID.randomUUID().toString().replace("-", ""));

    Provider saved = providerRepository.save(provider);
    telegramNotifyService.notifyProviderSelfRegistered(saved);
    return saved;
  }

  private String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " requerido");
    }
    return value.trim();
  }

  private String validateLength(String value, int max, String field) {
    if (value.length() > max) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " demasiado largo (máx " + max + ")");
    }
    return value;
  }

  private String validatePhone(String phone) {
    String clean = requireText(phone, "teléfono");
    String digits = clean.replaceAll("\\D", "");
    if (digits.length() < 6 || digits.length() > 15) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "teléfono inválido");
    }
    return clean;
  }

  /** Solo ids reales del catálogo (ServiceCategory), sin "otro": el proveedor ofrece oficios concretos. */
  private String validateCategories(List<String> categories) {
    if (categories == null || categories.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "elegí al menos una categoría");
    }
    List<String> clean = categories.stream()
        .map(c -> c == null ? "" : c.trim().toLowerCase())
        .distinct()
        .toList();
    for (String category : clean) {
      if ("otro".equals(category) || ServiceCategory.fromId(category).isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoría desconocida: " + category);
      }
    }
    return String.join(",", clean);
  }
}
