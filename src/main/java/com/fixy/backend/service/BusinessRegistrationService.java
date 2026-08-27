package com.fixy.backend.service;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessCategory;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.repository.BusinessRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Autoregistro público de comercios (Fase 1+2 "puerta única de registro",
 * Carlos 2026-08-27) — espejo casi exacto de {@link ProviderRegistrationService}
 * para el lado comercio, con una diferencia deliberada de negocio: el
 * comercio nace {@code ACTIVE} directo (no {@code INACTIVE} pendiente de
 * aprobación como el proveedor) porque la superficie pública no cambia —
 * las ofertas del comercio siguen moderadas una por una
 * ({@code OfferService.approve}), el alta del comercio en sí no publica
 * nada. Google ancla la identidad desde el arranque (nunca hay link mágico
 * que compartir, mismo argumento que proveedor) y devuelve el panelToken de
 * una — mismo {@link BusinessService#ensurePanel} que usa
 * {@link BusinessGoogleAuthService#login}.
 *
 * <p>Si el sub de Google YA está vinculado a un comercio, esto es un login
 * implícito (mismo argumento que {@link BusinessGoogleAuthService#login}):
 * no se crea nada, se devuelve el existente con {@code alreadyExisted=true}
 * y ninguno de los demás campos del request se valida ni se usa.
 *
 * <p>El check de {@code verifier.isEnabled()} es EXPLÍCITO (503 distinguible
 * de "credential inválido") — mismo patrón que {@link BusinessGoogleAuthService},
 * deliberadamente distinto del precedente de proveedor (que no distingue
 * los dos casos, ver su javadoc).
 */
@Service
public class BusinessRegistrationService {

  private static final int NAME_MIN = 2;
  private static final int NAME_MAX = 150;
  private static final int ZONE_MAX = 100;
  private static final int ADDRESS_MAX = 255;

  private final GoogleIdTokenVerifierService verifier;
  private final BusinessRepository businessRepository;
  private final BusinessService businessService;
  private final PublicLeadAbuseProtectionService abuseProtectionService;
  private final TelegramNotifyService telegramNotifyService;

  public BusinessRegistrationService(
      GoogleIdTokenVerifierService verifier,
      BusinessRepository businessRepository,
      BusinessService businessService,
      PublicLeadAbuseProtectionService abuseProtectionService,
      TelegramNotifyService telegramNotifyService
  ) {
    this.verifier = verifier;
    this.businessRepository = businessRepository;
    this.businessService = businessService;
    this.abuseProtectionService = abuseProtectionService;
    this.telegramNotifyService = telegramNotifyService;
  }

  public BusinessRegistrationResult register(
      String credential,
      String name,
      String whatsappNumber,
      String category,
      String zone,
      String address,
      String clientIp
  ) {
    // Rate limit primero (mismo criterio que el resto de la familia
    // pública): frena spam/fuerza-bruta antes de gastar una verificación
    // contra Google.
    abuseProtectionService.validateBusinessRegistration(clientIp);

    GoogleIdTokenVerifierService.GoogleIdentity identity = verifyOrThrow(credential);

    Business existing = businessRepository.findByGoogleSub(identity.sub()).orElse(null);
    if (existing != null) {
      Business withPanel = businessService.ensurePanel(existing);
      return new BusinessRegistrationResult(withPanel, true);
    }

    String cleanName = validateName(name);
    String cleanZone = validateLength(requireText(zone, "zona"), ZONE_MAX, "zona");
    String cleanWhatsapp = validatePhone(whatsappNumber);
    BusinessCategory cleanCategory = BusinessCategory.fromId(category)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoría desconocida: " + category));
    String cleanAddress = hasText(address) ? validateLength(address.trim(), ADDRESS_MAX, "dirección") : null;

    String normalizedPhone = cleanWhatsapp.replaceAll("\\D", "");
    Business existingByPhone = businessRepository.findByWhatsappNumber(normalizedPhone).orElse(null);
    if (existingByPhone != null) {
      // No se reclama un comercio ajeno solo por conocer su WhatsApp
      // público — ops lo resuelve a mano. Aviso proactivo, mismo criterio
      // que ProviderRegistrationService con teléfono duplicado.
      telegramNotifyService.notifyExistingBusinessRegistrationAttempt(existingByPhone, identity.email());
      throw new PhoneInUseException("ese WhatsApp ya está registrado en Fixy — si es tuyo, escribinos");
    }

    Business business = new Business();
    business.setName(cleanName);
    business.setWhatsappNumber(cleanWhatsapp);
    business.setCategory(cleanCategory.id());
    business.setPrimaryZone(cleanZone);
    business.setAddress(cleanAddress);
    business.setStatus(BusinessStatus.ACTIVE);
    business.setGoogleSub(identity.sub());
    business.setGoogleEmail(identity.email());

    Business saved = businessRepository.save(business);
    Business withPanel = businessService.ensurePanel(saved);
    telegramNotifyService.notifyBusinessSelfRegistered(withPanel);
    return new BusinessRegistrationResult(withPanel, false);
  }

  public record BusinessRegistrationResult(Business business, boolean alreadyExisted) {
  }

  private GoogleIdTokenVerifierService.GoogleIdentity verifyOrThrow(String credential) {
    if (!verifier.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "google auth not configured");
    }
    if (!hasText(credential)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "credential requerido");
    }
    return verifier.verify(credential)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "credential de Google inválido"));
  }

  private String requireText(String value, String field) {
    if (!hasText(value)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " requerido");
    }
    return value.trim();
  }

  private String validateName(String name) {
    String clean = requireText(name, "nombre");
    if (clean.length() < NAME_MIN) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nombre demasiado corto");
    }
    return validateLength(clean, NAME_MAX, "nombre");
  }

  private String validateLength(String value, int max, String field) {
    if (value.length() > max) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " demasiado largo (máx " + max + ")");
    }
    return value;
  }

  private String validatePhone(String phone) {
    String clean = requireText(phone, "whatsapp");
    String digits = clean.replaceAll("\\D", "");
    if (digits.length() < 6 || digits.length() > 15) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "whatsapp inválido");
    }
    return clean;
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isBlank();
  }
}
