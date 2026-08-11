package com.fixy.backend.service;

import com.fixy.backend.dto.OfferCreateRequest;
import com.fixy.backend.dto.OfferIngestItem;
import com.fixy.backend.dto.OfferIngestRequest;
import com.fixy.backend.dto.OfferIngestResponse;
import com.fixy.backend.dto.OfferPublicResponse;
import com.fixy.backend.dto.OfferResponse;
import com.fixy.backend.dto.OfferUpdateRequest;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(OfferService.class);

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
    // "Vale en todas las zonas" (cadenas con presencia en toda CdlC) — distinto
    // de zona faltante: una oferta sin zona Y sin allZones sigue excluida.
    if (offer.isAllZones()) {
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
            business.getName(),
            offer.getSourceName()
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

  /**
   * Ingesta idempotente de ofertas scrapeadas de fuentes públicas curadas
   * (bancos uruguayos) — ver maquina/scripts/ofertas-fuentes/. Publicación
   * SIEMPRE mediada por aprobación humana: toda oferta nueva nace DRAFT
   * (mismo {@code Offer.prePersist} que cualquier otro origen); una oferta
   * ya aprobada (ACTIVE) NUNCA se pisa sola.
   *
   * <p>Reglas (una pasada por item, dedup por {@code externalKey}):
   * <ul>
   *   <li>no existe → crea DRAFT.</li>
   *   <li>existe y sigue en DRAFT → refresca sus datos (título, vigencia,
   *   zona, etc.) con lo último de la fuente.</li>
   *   <li>existe pero ya salió de DRAFT (ACTIVE/REJECTED/EXPIRED) → NO se
   *   toca; una aprobación humana no se revierte por una corrida
   *   automática.</li>
   * </ul>
   *
   * <p>Limpieza de cola: se asume que cada corrida manda el listado COMPLETO
   * vigente de cada fuente presente en el request. Para cada
   * {@code sourceName} del batch, toda oferta {@code ORIGIN_SCRAPED_SOURCE}
   * de esa fuente que NO vino en esta corrida es candidata: si sigue en
   * DRAFT se marca REJECTED (ya no está vigente en la fuente, no tiene
   * sentido dejarla pendiente de aprobación); si es ACTIVE no se toca —
   * se reporta en {@code stillActiveMissingFromSource} para que ops decida
   * (puede seguir vigente en el local aunque el banco haya bajado la promo
   * de su web).
   */
  public OfferIngestResponse ingest(OfferIngestRequest request) {
    int created = 0;
    int refreshed = 0;
    Set<String> seenKeysBySource = new HashSet<>();
    Set<String> sourceNamesInBatch = new LinkedHashSet<>();

    for (OfferIngestItem item : request.offers()) {
      sourceNamesInBatch.add(item.sourceName());
      seenKeysBySource.add(item.sourceName() + " " + item.externalKey());

      Offer existing = offerRepository.findByExternalKey(item.externalKey()).orElse(null);
      if (existing == null) {
        Offer offer = new Offer();
        offer.setBusinessId(findOrCreateBusiness(item).getId());
        applyIngestFields(offer, item);
        offer.setOrigin(Offer.ORIGIN_SCRAPED_SOURCE);
        offerRepository.save(offer);
        created++;
      } else if (existing.getStatus() == OfferStatus.DRAFT) {
        applyIngestFields(existing, item);
        offerRepository.save(existing);
        refreshed++;
        log.info("ingesta de ofertas: refrescada id={} externalKey={} source={}",
            existing.getId(), item.externalKey(), item.sourceName());
      }
      // ACTIVE/REJECTED/EXPIRED: aprobación humana ya corrió, no se toca.
    }

    int discarded = 0;
    List<Long> stillActiveMissingFromSource = new ArrayList<>();
    for (String sourceName : sourceNamesInBatch) {
      for (Offer offer : offerRepository.findByOriginAndSourceName(Offer.ORIGIN_SCRAPED_SOURCE, sourceName)) {
        String key = sourceName + " " + offer.getExternalKey();
        if (seenKeysBySource.contains(key)) {
          continue;
        }
        if (offer.getStatus() == OfferStatus.DRAFT) {
          offer.setStatus(OfferStatus.REJECTED);
          offerRepository.save(offer);
          discarded++;
        } else if (offer.getStatus() == OfferStatus.ACTIVE) {
          stillActiveMissingFromSource.add(offer.getId());
        }
      }
    }

    return new OfferIngestResponse(created, refreshed, discarded, stillActiveMissingFromSource);
  }

  private void applyIngestFields(Offer offer, OfferIngestItem item) {
    offer.setExternalKey(item.externalKey());
    offer.setSourceName(item.sourceName());
    offer.setSourceUrl(trimToNull(item.sourceUrl()));
    offer.setTitle(item.title().trim());
    offer.setCategory(item.category().trim());
    offer.setZone(trimToNull(item.zone()));
    offer.setAllZones(item.allZones());
    offer.setDescription(trimToNull(item.description()));
    offer.setDiscountText(trimToNull(item.discountText()));
    offer.setValidFrom(item.validFrom());
    offer.setValidUntil(item.validUntil());
  }

  /**
   * Find-or-create del {@link Business} de una oferta scrapeada: el scraper
   * solo conoce el nombre curado del comercio (merchants.yaml), no un id de
   * Fixy. {@code whatsappNumber} es NOT NULL en el modelo pero un comercio
   * scrapeado de la web de un banco no tiene WhatsApp conocido — se usa un
   * valor sintético marcado ("scraped:<slug>"), nunca un número real.
   */
  private Business findOrCreateBusiness(OfferIngestItem item) {
    String name = item.businessName().trim();
    return businessRepository.findByNameIgnoreCase(name).orElseGet(() -> {
      Business business = new Business();
      business.setName(name);
      business.setWhatsappNumber("scraped:" + slug(name));
      business.setCategory(defaultIfBlank(item.businessCategory(), "otro"));
      business.setStatus(BusinessStatus.ACTIVE);
      return businessRepository.save(business);
    });
  }

  private String slug(String value) {
    String normalized = java.text.Normalizer.normalize(value.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("(^-|-$)", "");
    return normalized.isBlank() ? "comercio" : normalized;
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
        offer.isAllZones(),
        offer.getDescription(),
        offer.getDiscountText(),
        offer.getValidFrom(),
        offer.getValidUntil(),
        offer.getPhotoUrl(),
        offer.getStatus(),
        offer.getOrigin(),
        offer.getSourceMessageRaw(),
        offer.getSourceName(),
        offer.getSourceUrl(),
        offer.getExternalKey(),
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
