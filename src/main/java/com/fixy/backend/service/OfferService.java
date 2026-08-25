package com.fixy.backend.service;

import com.fixy.backend.dto.OfferAnalysis;
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
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.OfferInquiryRepository;
import com.fixy.backend.repository.OfferRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

  /**
   * Ruteo determinista del CTA de una oferta (FIXY_OFERTAS_CTA_DESIGN.md
   * §2), calculado a partir del {@link Business} vinculado. Nunca se deriva
   * en el cliente — el DTO público solo lleva el string resultante.
   */
  public enum OfferCtaType { PROVIDER, COMERCIO, NONE }

  private final OfferRepository offerRepository;
  private final BusinessRepository businessRepository;
  private final LeadRepository leadRepository;
  private final OfferInquiryRepository offerInquiryRepository;
  private final OfferRankingService offerRankingService;
  private final OfferAnalysisService offerAnalysisService;
  private final Clock clock;
  private final Path uploadsRoot;
  private final String urlPrefix;
  private final int socialProofMinViews;
  private final SecureRandom random = new SecureRandom();

  public OfferService(
      OfferRepository offerRepository,
      BusinessRepository businessRepository,
      LeadRepository leadRepository,
      OfferInquiryRepository offerInquiryRepository,
      OfferRankingService offerRankingService,
      OfferAnalysisService offerAnalysisService,
      Clock clock,
      @Value("${fixy.uploads.dir:./data/uploads}") String uploadsDir,
      @Value("${fixy.uploads.url-prefix:/uploads}") String urlPrefix,
      @Value("${fixy.offers.social-proof-min-views:10}") int socialProofMinViews
  ) {
    this.offerRepository = offerRepository;
    this.businessRepository = businessRepository;
    this.leadRepository = leadRepository;
    this.offerInquiryRepository = offerInquiryRepository;
    this.offerRankingService = offerRankingService;
    this.offerAnalysisService = offerAnalysisService;
    this.clock = clock;
    this.uploadsRoot = Path.of(uploadsDir).toAbsolutePath().normalize();
    this.urlPrefix = urlPrefix.replaceAll("/+$", "");
    this.socialProofMinViews = socialProofMinViews;
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
   *
   * <p>Orden: ya NO es cronológico. Se aplica {@link OfferRankingService}
   * (score de conveniencia, fase 1 + señal de interacción fase 3) sobre el
   * resultado ya filtrado por zona/categoría — el ranking nunca decide qué
   * entra a la lista, solo el orden. {@code inquiryCount} de cada oferta se
   * arma en un solo query agrupado ({@link OfferInquiryRepository#countGroupedByOfferId})
   * antes de rankear, para no hacer N+1 sobre el listado.
   */
  public List<OfferPublicResponse> listPublic(String zone, String category) {
    String normalizedZone = normalize(zone);
    String normalizedCategory = normalize(category);
    OffsetDateTime now = OffsetDateTime.now(clock);

    List<Offer> filtered = offerRepository.findByStatusAndValidUntilAfter(OfferStatus.ACTIVE, now).stream()
        .filter(offer -> matchesPublicZone(offer, normalizedZone))
        .filter(offer -> matchesPublicCategory(offer, normalizedCategory))
        .toList();

    Map<Long, Integer> inquiryCountsByOfferId = inquiryCountsFor(filtered);
    Map<Long, OfferAnalysis> analysisByOfferId = offerAnalysisService.analyze(filtered, now);

    return offerRankingService.rank(filtered, now, inquiryCountsByOfferId).stream()
        .map(offer -> toPublicResponse(
            offer, inquiryCountsByOfferId.getOrDefault(offer.getId(), 0), analysisByOfferId.get(offer.getId())))
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  /** Mapa offerId -> inquiryCount de un batch de ofertas, un solo query agrupado (ver {@link #listPublic}). */
  private Map<Long, Integer> inquiryCountsFor(List<Offer> offers) {
    if (offers.isEmpty()) {
      return Map.of();
    }
    List<Long> offerIds = offers.stream().map(Offer::getId).toList();
    Map<Long, Integer> counts = new HashMap<>();
    for (Object[] row : offerInquiryRepository.countGroupedByOfferId(offerIds)) {
      counts.put((Long) row[0], ((Long) row[1]).intValue());
    }
    return counts;
  }

  /**
   * Detalle público de una oferta puntual — mismo DTO y mismo criterio que
   * {@link #listPublic}: 200 solo si {@code ACTIVE} y {@code validUntil} no
   * vencida. Cualquier otro caso (no existe, draft, rejected, expired, o
   * activa mas vencida) es 404 sin distinguir el motivo — no hay que
   * filtrar el estado interno de una oferta a través del código de error.
   */
  public OfferPublicResponse getPublic(Long id) {
    Offer offer = offerRepository.findById(id).orElse(null);
    if (offer == null || offer.getStatus() != OfferStatus.ACTIVE) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found");
    }
    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime validUntil = offer.getValidUntil();
    if (validUntil == null || !validUntil.isAfter(now)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found");
    }
    OfferAnalysis analysis = offerAnalysisService.analyze(List.of(offer), now).get(offer.getId());
    OfferPublicResponse response = toPublicResponse(
        offer, offerInquiryRepository.countByOfferId(offer.getId()), analysis);
    if (response == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found");
    }
    return response;
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

  /**
   * "Me sirve" del cliente (fase 3): mismo patrón fire-and-forget que
   * {@link #registerView}/{@link #registerClick} (misma existencia-solamente
   * como regla, sin chequeo de vigencia ni protección anti-abuso — ninguno
   * de los otros dos la tiene), pero con incremento atómico vía UPDATE
   * ({@link OfferRepository#incrementLikeCount}) en vez de read-modify-write:
   * a diferencia de esos, likeCount alimenta el ranking (fase 3), así que un
   * incremento perdido bajo concurrencia sí le pega al score.
   */
  public void registerLike(Long id) {
    if (offerRepository.incrementLikeCount(id) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found");
    }
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
  private OfferPublicResponse toPublicResponse(Offer offer, int inquiryCount, OfferAnalysis analysis) {
    return businessRepository.findById(offer.getBusinessId())
        .map(business -> new OfferPublicResponse(
            offer.getId(),
            offer.getTitle(),
            offer.getDiscountText(),
            offer.getDescription(),
            offer.getCategory(),
            offer.getZone(),
            offer.isAllZones(),
            offer.getPhotoUrl(),
            offer.getValidUntil(),
            business.getName(),
            offer.getSourceName(),
            business.getAddress(),
            offer.getViewCount() >= socialProofMinViews ? offer.getViewCount() : null,
            ctaTypeLabel(ctaType(business)),
            offer.getCreatedAt(),
            offer.getLikeCount(),
            inquiryCount,
            business.getLatitude(),
            business.getLongitude(),
            analysis
        ))
        .orElse(null);
  }

  /**
   * Ruteo determinista del CTA (FIXY_OFERTAS_CTA_DESIGN.md §2). El orden de
   * evaluación importa: {@code providerId} es la fuente de verdad más
   * fuerte, se evalúa primero — un comercio que también es Provider
   * (vínculo explícito, ver javadoc de {@link Business}) siempre cae en
   * PROVIDER, coherente con que esa es la vía de mayor valor (un lead real
   * matcheable) y evita tratar redundantemente su whatsappNumber como
   * "comercio con contacto real".
   */
  private OfferCtaType ctaType(Business business) {
    if (business.getProviderId() != null) {
      return OfferCtaType.PROVIDER;
    }
    String wa = business.getWhatsappNumber();
    if (wa != null && wa.startsWith("scraped:")) {
      return OfferCtaType.NONE;
    }
    return OfferCtaType.COMERCIO;
  }

  private String ctaTypeLabel(OfferCtaType type) {
    return switch (type) {
      case PROVIDER -> "provider";
      case COMERCIO -> "comercio";
      case NONE -> "none";
    };
  }

  /**
   * Usado por {@code OfferInquiryService.create}: misma validación que
   * {@link #getPublic} (ACTIVE + vigente) más el chequeo de que el
   * {@code ctaType} de la oferta sea COMERCIO — defensa en profundidad, el
   * backend no confía en que el frontend solo muestre el mini-form ahí.
   * Devuelve la entidad (no el DTO público) porque la ruta comercio necesita
   * {@code businessId} para denormalizarlo en {@code OfferInquiry}, dato que
   * el DTO público nunca expone.
   */
  public Offer findActiveOfferForInquiry(Long id) {
    Offer offer = offerRepository.findById(id).orElse(null);
    if (offer == null || offer.getStatus() != OfferStatus.ACTIVE) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found");
    }
    OffsetDateTime validUntil = offer.getValidUntil();
    if (validUntil == null || !validUntil.isAfter(OffsetDateTime.now(clock))) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found");
    }
    Business business = businessRepository.findById(offer.getBusinessId()).orElse(null);
    if (business == null || ctaType(business) != OfferCtaType.COMERCIO) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "offer does not accept inquiries");
    }
    return offer;
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
   *   <li>existe y está ACTIVE → se le EXTIENDE la vigencia (solo hacia
   *   adelante, ver {@link #extendValidity}) porque la fuente sigue
   *   publicando el beneficio. El contenido aprobado no se pisa. Sin esto
   *   una oferta aprobada quedaba con el {@code validUntil} congelado del
   *   día que nació como borrador y se moría aunque el banco la siguiera
   *   listando — el catálogo entero se vaciaba solo (caso real: las 23
   *   ofertas del 13/08/2026, ver CURRENT_WORK.md).</li>
   *   <li>existe y está EXPIRED pero la fuente la sigue publicando → vuelve
   *   a DRAFT con datos frescos, para que ops la re-apruebe. Antes quedaba
   *   muerta para siempre: su {@code externalKey} ya existía, así que la
   *   ingesta nunca volvía a generarle un borrador.</li>
   *   <li>existe y está REJECTED → NO se toca. Un humano la rechazó a
   *   propósito y una corrida automática no revierte esa decisión.</li>
   * </ul>
   *
   * <p>Invariante que se mantiene intacta: <b>nada de este endpoint pasa a
   * ACTIVE por sí solo</b>. Lo único que se toca de una oferta ya aprobada
   * es la fecha de vencimiento, nunca su contenido ni su estado.
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
    int revalidated = 0;
    int reopened = 0;
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
      } else if (existing.getStatus() == OfferStatus.ACTIVE) {
        if (extendValidity(existing, item.validUntil())) {
          offerRepository.save(existing);
          revalidated++;
          log.info("ingesta de ofertas: vigencia extendida id={} externalKey={} nuevoValidUntil={}",
              existing.getId(), item.externalKey(), existing.getValidUntil());
        }
      } else if (existing.getStatus() == OfferStatus.EXPIRED) {
        applyIngestFields(existing, item);
        existing.setStatus(OfferStatus.DRAFT);
        offerRepository.save(existing);
        reopened++;
        log.info("ingesta de ofertas: vencida reabierta a draft id={} externalKey={} source={}",
            existing.getId(), item.externalKey(), item.sourceName());
      }
      // REJECTED: un humano la rechazó a propósito, la ingesta no la resucita.
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

    return new OfferIngestResponse(
        created, refreshed, revalidated, reopened, discarded, stillActiveMissingFromSource);
  }

  /**
   * Extiende la vigencia de una oferta YA APROBADA que la fuente sigue
   * publicando. Solo mueve {@code validUntil} hacia adelante: el contenido
   * que un humano aprobó (título, descripción, zona, descuento) NO se toca
   * acá — eso sigue siendo terreno exclusivo de {@link #applyIngestFields}
   * sobre borradores.
   *
   * <p>Casos en los que no hace nada, a propósito:
   * <ul>
   *   <li>la fuente no manda {@code validUntil} → no hay con qué extender;</li>
   *   <li>la oferta no vence ({@code validUntil} null) → ponerle fecha la ACORTARÍA;</li>
   *   <li>la fecha de la fuente no es posterior a la vigente → idem, nunca se
   *   recorta la vigencia de algo aprobado.</li>
   * </ul>
   *
   * @return true si movió la fecha (el caller persiste y cuenta).
   */
  private boolean extendValidity(Offer offer, OffsetDateTime validUntilFromSource) {
    if (validUntilFromSource == null || offer.getValidUntil() == null) {
      return false;
    }
    if (!validUntilFromSource.isAfter(offer.getValidUntil())) {
      return false;
    }
    offer.setValidUntil(validUntilFromSource);
    return true;
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
        offer.getLikeCount(),
        (int) leadRepository.countBySourceOfferId(offer.getId()),
        offerInquiryRepository.countByOfferId(offer.getId()),
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
