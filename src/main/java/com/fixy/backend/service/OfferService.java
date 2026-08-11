package com.fixy.backend.service;

import com.fixy.backend.dto.OfferCreateRequest;
import com.fixy.backend.dto.OfferPublicResponse;
import com.fixy.backend.dto.OfferResponse;
import com.fixy.backend.dto.OfferUpdateRequest;
import com.fixy.backend.model.CoverageZone;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * CRUD admin de {@link Offer} + cola de aprobación (diseño
 * FIXY_OFERTAS_INGESTA_DESIGN.md §3.2 y §4.3). Toda oferta nace en
 * {@code DRAFT}, sea cual sea su origen — ninguna pasa a {@code ACTIVE} sin
 * la acción explícita {@link #approve}.
 */
@Service
public class OfferService {

  /** Vigencia default cuando ops aprueba sin cargar validUntil (diseño §5). */
  private static final long DEFAULT_VALIDITY_DAYS = 14;

  /** Mismo límite y mismos tipos que LeadPhotoService — sin razón para divergir. */
  private static final long MAX_PHOTO_BYTES = 6 * 1024 * 1024; // 6 MB
  private static final Set<String> ALLOWED_PHOTO_CONTENT_TYPES = Set.of(
      "image/jpeg", "image/jpg", "image/png", "image/webp"
  );

  private final OfferRepository offerRepository;
  private final BusinessRepository businessRepository;
  private final Clock clock;
  private final Path uploadsRoot;
  private final String urlPrefix;
  private final SecureRandom random = new SecureRandom();

  public OfferService(
      OfferRepository offerRepository,
      BusinessRepository businessRepository,
      Clock clock,
      @Value("${fixy.uploads.dir:./data/uploads}") String uploadsDir,
      @Value("${fixy.uploads.url-prefix:/uploads}") String urlPrefix
  ) {
    this.offerRepository = offerRepository;
    this.businessRepository = businessRepository;
    this.clock = clock;
    this.uploadsRoot = Path.of(uploadsDir).toAbsolutePath().normalize();
    this.urlPrefix = urlPrefix.replaceAll("/+$", "");
    try {
      Files.createDirectories(this.uploadsRoot);
    } catch (IOException e) {
      throw new IllegalStateException("cannot create uploads dir: " + this.uploadsRoot, e);
    }
  }

  public List<OfferResponse> list(String status) {
    List<Offer> offers = (status == null || status.isBlank())
        ? offerRepository.findAllByOrderByCreatedAtDesc()
        : offerRepository.findByStatusOrderByCreatedAtDesc(parseStatus(status));
    return offers.stream().map(this::toResponse).toList();
  }

  private OfferStatus parseStatus(String raw) {
    try {
      return OfferStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status");
    }
  }

  public OfferResponse get(Long id) {
    return toResponse(findOffer(id));
  }

  /**
   * Superficie pública de lectura (Loop 2 del roadmap, tarjeta de cierre de
   * pedido): solo ofertas {@code ACTIVE} con {@code validUntil} no vencida,
   * filtradas opcionalmente por zona/categoría. Zona usa
   * {@link CoverageZone#covers} (misma jerarquía paraguas/barrio que el
   * matching de proveedores); una oferta sin zona declarada no aparece en
   * un listado filtrado por zona — decisión deliberada, evita mostrar una
   * oferta fuera de contexto por un dato faltante en vez de por elección.
   */
  public List<OfferPublicResponse> listPublic(String zone, String category) {
    String normalizedZone = normalize(zone);
    String normalizedCategory = normalize(category);
    OffsetDateTime now = OffsetDateTime.now(clock);

    return offerRepository.findByStatusAndValidUntilAfter(OfferStatus.ACTIVE, now).stream()
        .filter(offer -> matchesPublicZone(offer, normalizedZone))
        .filter(offer -> matchesPublicCategory(offer, normalizedCategory))
        .sorted(Comparator.comparing(Offer::getValidUntil))
        .map(this::toPublicResponse)
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  /** Conteo de ofertas vigentes, sin filtros — barato, listo para el futuro flag del tab. */
  public long countPublic() {
    return offerRepository.countByStatusAndValidUntilAfter(OfferStatus.ACTIVE, OffsetDateTime.now(clock));
  }

  /** Contador simple, sin idempotencia (fire-and-forget desde el cliente). */
  public void registerView(Long id) {
    Offer offer = findOffer(id);
    offer.setViewCount(offer.getViewCount() + 1);
    offerRepository.save(offer);
  }

  /** Contador simple, sin idempotencia (fire-and-forget desde el cliente). */
  public void registerClick(Long id) {
    Offer offer = findOffer(id);
    offer.setClickCount(offer.getClickCount() + 1);
    offerRepository.save(offer);
  }

  private boolean matchesPublicZone(Offer offer, String normalizedZone) {
    if (normalizedZone.isBlank()) {
      return true;
    }
    String offerZone = offer.getZone();
    if (offerZone == null || offerZone.isBlank()) {
      return false;
    }
    return CoverageZone.covers(offerZone, normalizedZone);
  }

  private boolean matchesPublicCategory(Offer offer, String normalizedCategory) {
    if (normalizedCategory.isBlank()) {
      return true;
    }
    return normalize(offer.getCategory()).equals(normalizedCategory);
  }

  /** null si el Business referenciado no existe (huérfano) — se filtra en listPublic, no debería pasar en régimen normal. */
  private OfferPublicResponse toPublicResponse(Offer offer) {
    return businessRepository.findById(offer.getBusinessId())
        .map(business -> new OfferPublicResponse(
            offer.getId(),
            offer.getTitle(),
            offer.getDiscountText(),
            offer.getDescription(),
            offer.getCategory(),
            offer.getZone(),
            offer.getPhotoUrl(),
            offer.getValidUntil(),
            business.getName()
        ))
        .orElse(null);
  }

  private String normalize(String value) {
    return CoverageZone.normalize(value);
  }

  public OfferResponse create(OfferCreateRequest request) {
    if (!businessRepository.existsById(request.businessId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "business not found");
    }
    Offer offer = new Offer();
    offer.setBusinessId(request.businessId());
    offer.setTitle(request.title().trim());
    offer.setCategory(request.category().trim());
    offer.setZone(trimToNull(request.zone()));
    offer.setDescription(trimToNull(request.description()));
    offer.setDiscountText(trimToNull(request.discountText()));
    offer.setValidFrom(request.validFrom());
    offer.setValidUntil(request.validUntil());
    offer.setPhotoUrl(trimToNull(request.photoUrl()));
    offer.setOrigin(defaultIfBlank(request.origin(), Offer.ORIGIN_MANUAL));
    offer.setSourceMessageRaw(trimToNull(request.sourceMessageRaw()));
    offer.setStatus(OfferStatus.DRAFT);
    return toResponse(offerRepository.save(offer));
  }

  public OfferResponse update(Long id, OfferUpdateRequest request) {
    Offer offer = findOffer(id);

    if (request.title() != null) offer.setTitle(request.title().trim());
    if (request.category() != null) offer.setCategory(request.category().trim());
    if (request.zone() != null) offer.setZone(trimToNull(request.zone()));
    if (request.description() != null) offer.setDescription(trimToNull(request.description()));
    if (request.discountText() != null) offer.setDiscountText(trimToNull(request.discountText()));
    if (request.validFrom() != null) offer.setValidFrom(request.validFrom());
    if (request.validUntil() != null) offer.setValidUntil(request.validUntil());
    if (request.photoUrl() != null) offer.setPhotoUrl(trimToNull(request.photoUrl()));
    if (request.sourceMessageRaw() != null) offer.setSourceMessageRaw(trimToNull(request.sourceMessageRaw()));

    return toResponse(offerRepository.save(offer));
  }

  /**
   * draft → active. Regla de negocio no negociable (diseño §5): una oferta
   * NUNCA puede quedar {@code ACTIVE} sin {@code validUntil}. Si ops no la
   * cargó, se completa automáticamente acá: 14 días desde {@code validFrom},
   * o desde el momento de la aprobación si tampoco hay {@code validFrom}.
   */
  public OfferResponse approve(Long id) {
    Offer offer = findOffer(id);
    if (offer.getStatus() != OfferStatus.DRAFT) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "solo se puede aprobar una oferta en draft (estado actual: " + offer.getStatus() + ")");
    }
    OffsetDateTime now = OffsetDateTime.now(clock);
    if (offer.getValidFrom() == null) {
      offer.setValidFrom(now);
    }
    if (offer.getValidUntil() == null) {
      OffsetDateTime base = offer.getValidFrom() != null ? offer.getValidFrom() : now;
      offer.setValidUntil(base.plusDays(DEFAULT_VALIDITY_DAYS));
    }
    offer.setStatus(OfferStatus.ACTIVE);
    return toResponse(offerRepository.save(offer));
  }

  /** draft → rejected. Sin acción sobre ofertas que ya salieron de draft. */
  public OfferResponse reject(Long id) {
    Offer offer = findOffer(id);
    if (offer.getStatus() != OfferStatus.DRAFT) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "solo se puede rechazar una oferta en draft (estado actual: " + offer.getStatus() + ")");
    }
    offer.setStatus(OfferStatus.REJECTED);
    return toResponse(offerRepository.save(offer));
  }

  /**
   * Sube/reemplaza la foto de una oferta (mismo patrón de storage que
   * {@code LeadPhotoService.store}: uploads/ + urlPrefix, nombre random,
   * validación de tipo/tamaño, chequeo de path traversal). No borra el
   * archivo anterior si lo hubiera — mismo criterio liviano que el resto
   * del repo, el huérfano no tiene costo real a este volumen.
   */
  public OfferResponse uploadPhoto(Long id, MultipartFile file) {
    Offer offer = findOffer(id);
    validatePhoto(file);

    String extension = extensionFor(file.getContentType(), file.getOriginalFilename());
    String relative = "offer-" + id + "/" + randomHex(8) + extension;
    Path target = uploadsRoot.resolve(relative).normalize();
    if (!target.startsWith(uploadsRoot)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid filename");
    }

    try {
      Files.createDirectories(target.getParent());
      try (var in = file.getInputStream()) {
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "no se pudo guardar la foto");
    }

    offer.setPhotoUrl(urlPrefix + "/" + relative);
    return toResponse(offerRepository.save(offer));
  }

  private void validatePhoto(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "archivo vacio");
    }
    if (file.getSize() > MAX_PHOTO_BYTES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "archivo demasiado grande (max 6 MB)");
    }
    String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
    if (!ALLOWED_PHOTO_CONTENT_TYPES.contains(ct)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "formato no permitido (usa jpg, png o webp)");
    }
  }

  private String extensionFor(String contentType, String original) {
    String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    return switch (ct) {
      case "image/jpeg", "image/jpg" -> ".jpg";
      case "image/png" -> ".png";
      case "image/webp" -> ".webp";
      default -> {
        if (original == null) yield "";
        int dot = original.lastIndexOf('.');
        yield dot >= 0 ? original.substring(dot).toLowerCase(Locale.ROOT) : "";
      }
    };
  }

  private String randomHex(int bytes) {
    byte[] buf = new byte[bytes];
    random.nextBytes(buf);
    return HexFormat.of().formatHex(buf);
  }

  private Offer findOffer(Long id) {
    return offerRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found"));
  }

  private OfferResponse toResponse(Offer offer) {
    return new OfferResponse(
        offer.getId(),
        offer.getBusinessId(),
        offer.getTitle(),
        offer.getCategory(),
        offer.getZone(),
        offer.getDescription(),
        offer.getDiscountText(),
        offer.getValidFrom(),
        offer.getValidUntil(),
        offer.getPhotoUrl(),
        offer.getStatus(),
        offer.getOrigin(),
        offer.getSourceMessageRaw(),
        offer.getViewCount(),
        offer.getClickCount(),
        offer.getCreatedAt(),
        offer.getUpdatedAt()
    );
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isBlank() ? null : trimmed;
  }

  private String defaultIfBlank(String value, String fallback) {
    String trimmed = trimToNull(value);
    return trimmed == null ? fallback : trimmed;
  }
}
