package com.fixy.backend.service;

import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.dto.ProviderCreateRequest;
import com.fixy.backend.dto.ProviderResponse;
import com.fixy.backend.dto.ProviderUpdateRequest;
import com.fixy.backend.model.AvailabilityWindows;
import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.CoverageZone;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderLeadDecline;
import com.fixy.backend.model.ProviderOffer;
import com.fixy.backend.model.ProviderOfferResponse;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.ProviderLeadDeclineRepository;
import com.fixy.backend.repository.ProviderOfferRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProviderCatalogService {

  private static final ZoneId MONTEVIDEO = ZoneId.of("America/Montevideo");

  private final ProviderRepository providerRepository;
  private final LeadPaymentRepository leadPaymentRepository;
  private final ProviderLeadDeclineRepository declineRepository;

  private final com.fixy.backend.repository.LeadRatingRepository leadRatingRepository;
  private final ProviderOfferRepository providerOfferRepository;

  /** Tier 2 (contrato §A.3): tamaño de la muestra de ofertas en ventana
   * usada para el score de respuesta (mediana de minutos). */
  private final int responseSampleSize;
  /** Con menos ofertas en ventana que esto, el proveedor usa el prior
   * ({@link #responsePriorMinutes}) — ni premio ni castigo al nuevo. */
  private final int responseMinSample;
  /** Minutos que usa un proveedor sin muestra suficiente (cold-start del
   * score de respuesta, análogo a {@code NEW_PROVIDER_RATING_PRIOR}). */
  private final int responsePriorMinutes;
  /** Tope de minutos: un TIMEOUT, una no-respuesta o una respuesta muy
   * lenta valen como máximo esto — un proveedor que tarda 10h no debe
   * pesar 100x más que uno que tarda 1h en el promedio. */
  private final int responseCapMinutes;

  public ProviderCatalogService(
      ProviderRepository providerRepository,
      LeadPaymentRepository leadPaymentRepository,
      ProviderLeadDeclineRepository declineRepository,
      com.fixy.backend.repository.LeadRatingRepository leadRatingRepository,
      ProviderOfferRepository providerOfferRepository,
      @Value("${fixy.matching.response.sample-size:10}") int responseSampleSize,
      @Value("${fixy.matching.response.min-sample:3}") int responseMinSample,
      @Value("${fixy.matching.response.prior-minutes:30}") int responsePriorMinutes,
      @Value("${fixy.matching.response.cap-minutes:240}") int responseCapMinutes) {
    this.providerRepository = providerRepository;
    this.leadPaymentRepository = leadPaymentRepository;
    this.declineRepository = declineRepository;
    this.leadRatingRepository = leadRatingRepository;
    this.providerOfferRepository = providerOfferRepository;
    this.responseSampleSize = responseSampleSize;
    this.responseMinSample = responseMinSample;
    this.responsePriorMinutes = responsePriorMinutes;
    this.responseCapMinutes = responseCapMinutes;
  }

  /**
   * Últimas reseñas CON TEXTO del proveedor (máx 2, anónimas) — prueba
   * social del preview público y de la ficha del asignado (UX 2026-08).
   * Mismo criterio de honestidad que el rating: solo reseñas reales.
   */
  public java.util.List<com.fixy.backend.dto.LeadResponse.ReviewSnippet> reviewSnippetsFor(Long providerId) {
    return leadRatingRepository.findByProviderId(providerId).stream()
        .filter(r -> r.getComment() != null && !r.getComment().isBlank())
        .sorted(java.util.Comparator.comparing(com.fixy.backend.model.LeadRating::getCreatedAt,
            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
        .limit(2)
        .map(r -> new com.fixy.backend.dto.LeadResponse.ReviewSnippet(
            r.getScore(), r.getComment().trim(), r.isVerified(), r.getProviderReply()))
        .toList();
  }

  public List<ProviderCatalogItem> list() {
    return providerRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(this::toCatalogItem)
        .toList();
  }

  public List<ProviderResponse> listDetailed() {
    return providerRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(this::toResponse)
        .toList();
  }

  public ProviderResponse get(Long id) {
    return toResponse(findProvider(id));
  }

  public ProviderResponse create(ProviderCreateRequest request) {
    Provider provider = new Provider();
    provider.setName(request.name().trim());
    provider.setPhone(request.phone().trim());
    provider.setWhatsappNumber(trimToNull(request.whatsappNumber()));
    provider.setSourceName(trimToNull(request.sourceName()));
    provider.setSourceType(defaultIfBlank(request.sourceType(), "manual"));
    provider.setPrimaryZone(trimToNull(request.primaryZone()));
    provider.setCoverageZones(normalizeCsv(request.coverageZones()));
    provider.setCity(trimToNull(request.city()));
    provider.setDepartment(trimToNull(request.department()));
    provider.setCategories(normalizeCsv(request.categories()));
    provider.setCategoryNotes(trimToNull(request.categoryNotes()));
    provider.setStatus(ProviderStatus.NEW);
    provider.setNotes(trimToNull(request.notes()));
    return toResponse(providerRepository.save(provider));
  }

  public ProviderResponse update(Long id, ProviderUpdateRequest request) {
    Provider provider = findProvider(id);

    if (request.name() != null) provider.setName(request.name().trim());
    if (request.phone() != null) provider.setPhone(request.phone().trim());
    if (request.whatsappNumber() != null) provider.setWhatsappNumber(trimToNull(request.whatsappNumber()));
    if (request.sourceName() != null) provider.setSourceName(trimToNull(request.sourceName()));
    if (request.sourceType() != null) provider.setSourceType(defaultIfBlank(request.sourceType(), "manual"));
    if (request.primaryZone() != null) provider.setPrimaryZone(trimToNull(request.primaryZone()));
    if (request.coverageZones() != null) provider.setCoverageZones(normalizeCsv(request.coverageZones()));
    if (request.city() != null) provider.setCity(trimToNull(request.city()));
    if (request.department() != null) provider.setDepartment(trimToNull(request.department()));
    if (request.categories() != null) provider.setCategories(normalizeCsv(request.categories()));
    if (request.categoryNotes() != null) provider.setCategoryNotes(trimToNull(request.categoryNotes()));
    if (request.status() != null) provider.setStatus(request.status());
    if (request.verificationStatus() != null) provider.setVerificationStatus(request.verificationStatus());
    if (request.ratingAverage() != null) provider.setRatingAverage(request.ratingAverage());
    if (request.ratingCount() != null) provider.setRatingCount(request.ratingCount());
    if (request.internalScore() != null) provider.setInternalScore(request.internalScore());
    if (request.riskFlags() != null) provider.setRiskFlags(normalizeCsv(request.riskFlags()));
    if (request.acceptedJobsCount() != null) provider.setAcceptedJobsCount(request.acceptedJobsCount());
    if (request.rejectedJobsCount() != null) provider.setRejectedJobsCount(request.rejectedJobsCount());
    if (request.completedJobsCount() != null) provider.setCompletedJobsCount(request.completedJobsCount());
    if (request.notes() != null) provider.setNotes(trimToNull(request.notes()));

    return toResponse(providerRepository.save(provider));
  }

  /**
   * Preview público para mostrar confianza al cliente: cuántos proveedores
   * matchean por (category, zone) y un sample corto con campos no-sensibles.
   * NO incluye teléfono ni notas.
   *
   * <p>Usa exactamente los mismos filtros de disponibilidad que
   * {@link #findMatches} ({@link #availableForNewWork}) porque son las dos
   * caras del mismo producto: lo que el preview promete tiene que ser lo que
   * el matching después entrega.
   */
  public com.fixy.backend.dto.ProviderPublicPreview publicPreview(String category, String zone, int sampleLimit) {
    String normalizedCategory = normalize(category);
    String normalizedLocation = normalize(zone);
    int limit = Math.max(0, Math.min(sampleLimit, 10));

    Set<Long> overdueProviderIds = leadPaymentRepository.findProviderIdsByCommissionStatus(CommissionStatus.OVERDUE);

    List<Provider> matched = providerRepository.findAll().stream()
        .filter(provider -> availableForNewWork(provider, overdueProviderIds))
        .filter(provider -> matchesCategory(provider, normalizedCategory))
        .filter(provider -> matchesLocation(provider, normalizedLocation))
        .toList();

    List<com.fixy.backend.dto.ProviderPublicPreview.Item> sample = matched.stream()
        .limit(limit)
        .map(p -> {
          // Honestidad en reputación: el prior de ranking (NEW_PROVIDER_RATING_PRIOR)
          // es solo para ordenar el matching internamente y nunca debe llegar acá.
          // Si no hay calificaciones reales, no exponer un promedio al cliente.
          int ratingCount = p.getRatingCount() == null ? 0 : p.getRatingCount();
          Double ratingAverage = ratingCount == 0 ? null : p.getRatingAverage();
          return new com.fixy.backend.dto.ProviderPublicPreview.Item(
              p.getName(),
              p.getPrimaryZone(),
              p.getCategories(),
              p.getCompletedJobsCount(),
              ratingAverage,
              ratingCount,
              reviewSnippetsFor(p.getId())
          );
        })
        .toList();

    return new com.fixy.backend.dto.ProviderPublicPreview(matched.size(), sample);
  }

  /**
   * Prior neutro para proveedores sin calificaciones (cold-start). Un
   * proveedor con ratingCount == 0 no tiene todavía señal real de calidad,
   * pero tampoco es "malo": si lo mandamos al fondo de la lista para
   * siempre, nunca junta su primer trabajo y la oferta nueva muere ahí.
   * Se lo trata como una nota decente (equivalente a 4.0), intercalado con
   * proveedores calificados en ese rango — ni privilegiado, ni enterrado.
   */
  private static final double NEW_PROVIDER_RATING_PRIOR = 4.0;

  public List<ProviderCatalogItem> findMatches(String category, String location) {
    String normalizedCategory = normalize(category);
    String normalizedLocation = normalize(location);
    // Comisión vencida pausa el matching (FIXY_COBRANZAS.md): un solo query
    // trae el set de providerIds con OVERDUE, evita un query por proveedor
    // dentro del stream. Trabajos ya asignados no pasan por acá — solo
    // afecta oportunidades nuevas.
    Set<Long> overdueProviderIds = leadPaymentRepository.findProviderIdsByCommissionStatus(CommissionStatus.OVERDUE);

    return providerRepository.findAll().stream()
        .filter(provider -> availableForNewWork(provider, overdueProviderIds))
        .filter(provider -> matchesCategory(provider, normalizedCategory))
        .filter(provider -> matchesLocation(provider, normalizedLocation))
        // Primero el que nombró TU barrio, después el que cubre la ciudad
        // entera. Desde que la jerarquía dejó entrar a los proveedores del
        // paraguas (Ciudad de la Costa), un generalista competía de igual a
        // igual con el que declaró la zona puntual; a igualdad de reputación
        // el vecino del barrio es mejor match y llega antes.
        .sorted((a, b) -> {
          int byZoneSpecificity = Boolean.compare(
              declaresZoneExactly(b, normalizedLocation), declaresZoneExactly(a, normalizedLocation));
          if (byZoneSpecificity != 0) {
            return byZoneSpecificity;
          }
          // Tier 2 (contrato §A.3): dentro de ventana ahora antes que fuera
          // — fuera NO se excluye (fill rate manda), solo se ordena después.
          int byWindow = Boolean.compare(isOpenNow(b), isOpenNow(a));
          if (byWindow != 0) {
            return byWindow;
          }
          // Puntaje de respuesta: menor es mejor.
          int byResponse = Integer.compare(responseScoreMinutes(a), responseScoreMinutes(b));
          return byResponse != 0 ? byResponse : Double.compare(rankingScore(b), rankingScore(a));
        })
        .map(provider -> toCatalogItem(provider, normalizedCategory))
        .toList();
  }

  /**
   * Score de orden por reputación (reorder-only, sin exclusividad ni
   * ventaja temporal — H_A). Proveedores sin calificaciones usan
   * {@link #NEW_PROVIDER_RATING_PRIOR} en vez de 0, para no quedar
   * enterrados al fondo por no tener historial todavía.
   */
  private double rankingScore(Provider provider) {
    Integer ratingCount = provider.getRatingCount();
    if (ratingCount == null || ratingCount == 0 || provider.getRatingAverage() == null) {
      return NEW_PROVIDER_RATING_PRIOR;
    }
    return provider.getRatingAverage();
  }

  /** Tier 2 (contrato §A.3): ¿este proveedor está dentro de su ventana
   * declarada AHORA (hora de Montevideo)? Sin ventana declarada = siempre. */
  public boolean isOpenNow(Provider provider) {
    return AvailabilityWindows.parse(provider.getAvailabilityWindows()).isOpenAt(ZonedDateTime.now(MONTEVIDEO));
  }

  /**
   * Tier 2 (contrato §A.3): mediana de minutos de respuesta sobre las
   * últimas {@link #responseSampleSize} ofertas EN VENTANA del proveedor.
   * {@code TIMEOUT} y las ofertas sin responder valen {@link
   * #responseCapMinutes}; cualquier respuesta se capea también a ese tope.
   * Con menos de {@link #responseMinSample} ofertas en ventana, usa {@link
   * #responsePriorMinutes} (ni premio ni castigo al proveedor nuevo). Menor
   * es mejor.
   */
  public int responseScoreMinutes(Provider provider) {
    List<Integer> sample = responseSampleMinutes(provider);
    if (sample.size() < responseMinSample) {
      return responsePriorMinutes;
    }
    return median(sample);
  }

  /** Cantidad real de ofertas en ventana consideradas por {@link
   * #responseScoreMinutes} (0..{@link #responseSampleSize}). Distinto de
   * {@link #responseScoreMinutes}: expone el tamaño de muestra crudo para
   * "Mis números", sin la sustitución por el prior. */
  public int responseSampleSizeFor(Provider provider) {
    return responseSampleMinutes(provider).size();
  }

  /** Minutos de respuesta (ya capeados) de la muestra en ventana, más
   * reciente primero — sin aplicar el prior. Lista vacía = sin muestra. */
  private List<Integer> responseSampleMinutes(Provider provider) {
    if (provider.getId() == null) {
      return List.of();
    }
    List<ProviderOffer> offers = providerOfferRepository
        .findByProviderIdAndInWindowTrueOrderByOfferedAtDesc(provider.getId());
    List<Integer> minutes = new ArrayList<>();
    for (int i = 0; i < offers.size() && i < responseSampleSize; i++) {
      minutes.add(minutesFor(offers.get(i)));
    }
    return minutes;
  }

  private int minutesFor(ProviderOffer offer) {
    if (offer.getResponse() == ProviderOfferResponse.TIMEOUT || offer.getRespondedAt() == null) {
      return responseCapMinutes;
    }
    long minutes = java.time.Duration.between(offer.getOfferedAt(), offer.getRespondedAt()).toMinutes();
    return (int) Math.min(Math.max(minutes, 0), responseCapMinutes);
  }

  /** "Mis números" (contrato §A.4): posición 1..N entre los pares del
   * proveedor (misma categoría, activos) según {@link #responseScoreMinutes}
   * — null si el proveedor todavía no tiene muestra propia. {@code peers}
   * es N (incluye al propio proveedor) y se devuelve siempre, tenga o no
   * muestra, para que el front pueda decir "con 3 pedidos contestados
   * aparece tu posición" con el universo ya conocido.
   *
   * Desvío del contrato: el contrato dice "proveedores ACTIVE/AVAILABLE",
   * pero {@link ProviderStatus} no tiene {@code ACTIVE} — se usa {@link
   * #canReceiveNewWork(Provider)} (misma fuente de verdad que el matching)
   * como filtro de pares en su lugar. Ver TIER2_CONTRATO.md, "Cambios
   * durante implementación".
   */
  public ResponseStanding responseStandingFor(Provider provider) {
    LinkedHashMap<Long, Provider> peers = new LinkedHashMap<>();
    if (provider.getId() != null) {
      peers.put(provider.getId(), provider);
    }
    providerRepository.findAll().stream()
        .filter(p -> p.getId() != null && !p.getId().equals(provider.getId()))
        .filter(this::canReceiveNewWork)
        .filter(p -> sharesAnyCategory(p, provider))
        .forEach(p -> peers.put(p.getId(), p));

    List<Provider> ordered = peers.values().stream()
        .sorted(Comparator.<Provider>comparingInt(this::responseScoreMinutes)
            .thenComparing(p -> p.getId() == null ? Long.MAX_VALUE : p.getId()))
        .toList();
    int peerCount = ordered.size();

    if (responseSampleSizeFor(provider) < responseMinSample) {
      return new ResponseStanding(null, peerCount);
    }
    int rank = 1;
    for (Provider p : ordered) {
      if (Objects.equals(p.getId(), provider.getId())) {
        break;
      }
      rank++;
    }
    return new ResponseStanding(rank, peerCount);
  }

  private boolean sharesAnyCategory(Provider a, Provider b) {
    Set<String> categoriesOfA = splitCsv(a.getCategories()).stream().map(this::normalize)
        .collect(Collectors.toSet());
    return splitCsv(b.getCategories()).stream().map(this::normalize).anyMatch(categoriesOfA::contains);
  }

  public record ResponseStanding(Integer rank, int peers) {
  }

  private int median(List<Integer> values) {
    List<Integer> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int size = sorted.size();
    int mid = size / 2;
    if (size % 2 == 0) {
      return (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }
    return sorted.get(mid);
  }

  /**
   * {@link #findMatches} para un lead concreto: además de los filtros de
   * catálogo, saca a los proveedores que YA rechazaron ESTE pedido.
   *
   * Existe porque la ausencia de este filtro causó un bug real (guardia del
   * 2026-07-29): el reintento de matching le volvió a ofrecer los leads
   * #128/#135/#147 a Carnot Clima, que los había declinado el 23/07 — el
   * cliente leyó "¡Buenas noticias! Apareció un proveedor" y 17 minutos
   * después el proveedor los rechazó de nuevo. La bandeja del proveedor
   * ({@code ProviderOpportunityService.listFor}) siempre respetó los
   * declines; el matching automático no, así que los dos caminos decían
   * cosas distintas sobre el mismo par (lead, proveedor).
   *
   * Todo camino que elija proveedor PARA UN LEAD debe usar este método;
   * {@link #findMatches} queda para las preguntas genéricas de cobertura
   * ("¿hay alguien en esta zona?"), donde no hay lead que rechazar.
   */
  public List<ProviderCatalogItem> findMatchesForLead(Long leadId, String category, String location) {
    List<ProviderCatalogItem> matches = findMatches(category, location);
    if (leadId == null || matches.isEmpty()) {
      return matches;
    }
    Set<Long> declinedBy = declineRepository.findByLeadId(leadId).stream()
        .map(ProviderLeadDecline::getProviderId)
        .collect(Collectors.toSet());
    if (declinedBy.isEmpty()) {
      return matches;
    }
    return matches.stream()
        .filter(item -> !declinedBy.contains(item.id()))
        .toList();
  }

  /**
   * Reverso de {@link #findMatches}: dado un provider ya cargado, ¿le sirve
   * este (categoría, zona)? Reusa la misma normalización y las mismas reglas
   * de matching (zona = primaryZone, coverageZones o city) para que
   * "¿qué leads le sirven a este proveedor?" (bandeja de oportunidades) no
   * diverja de "¿qué proveedores le sirven a este lead?" (matching de leads).
   */
  public boolean matchesProvider(Provider provider, String category, String location) {
    return matchesCategory(provider, normalize(category)) && matchesLocation(provider, normalize(location));
  }

  /**
   * ¿Este proveedor puede recibir trabajo nuevo hoy? Fuente única de los
   * filtros de disponibilidad (estado del padrón + comisión vencida), para que
   * el preview público y el matching no puedan volver a contestar distinto
   * sobre el mismo par (categoría, zona).
   *
   * <p>Existe porque la divergencia causó un caso real (guardia del
   * 2026-08-04): {@code GET /api/public/providers/preview?category=barometrica
   * &zone=Montes%20de%20Solymar} devolvía {@code count=1} con Barométrica
   * Nueva Era, y el mismo pedido por el chat (lead #204, el mismo minuto)
   * contestaba "Por ahora no tengo proveedores libres en Montes de Solymar
   * para barométrica" — Nueva Era estaba OVERDUE desde el 30/07 y solo
   * {@code findMatches} lo miraba. El preview es justo la superficie que
   * existe para dar confianza ANTES de pedir: prometer ahí a alguien que el
   * matching nunca va a asignar rompe la honestidad radical (principio 1 de
   * la Carta de Autonomía).
   *
   * <p>La pausa del proveedor ({@code acceptingWork=false}) entró acá el
   * 2026-08-05 por la misma clase de divergencia, medida sobre datos reales:
   * {@link ProviderOpportunityService#listFor} ya escondía las oportunidades
   * del que está en pausa, pero el matching seguía asignándole pedidos. A
   * Melissa (proveedora 10) le asignamos 6 pedidos de pastelería entre el
   * 15/07 y el 31/07 —y al cliente le dijimos "estoy contactando a Melissa"—
   * mientras su bandeja le mostraba cero. Los 6 terminaron CANCELLED. Si el
   * proveedor no puede VER el trabajo, el matching no puede prometerlo.
   *
   * <p>{@code NEW} = ficha cargada, todavía SIN aprobar, y entró acá el
   * 2026-08-06 (decisión de Carlos) para cerrar la otra mitad del mismo caso:
   * Melissa estuvo {@code NEW} desde su alta hasta que Carlos la aprobó el
   * 05/08, y durante esas tres semanas el matching le asignó los 6 pedidos y
   * le dijo al cliente "estoy contactando a Melissa" — sin que ningún humano
   * hubiera dado el visto bueno todavía. El autoregistro ya nace
   * {@code INACTIVE} y estaba cubierto ({@link ProviderRegistrationService});
   * el alta manual por la API de ops nace {@code NEW} ({@link #create}) y no
   * lo estaba. Ahora aprobar es lo que enciende el matching: {@code NEW} →
   * {@code AVAILABLE} en el admin. Importa hacia adelante, con la captación
   * dando de alta hasta 10 proveedores nuevos por semana.
   *
   * <p>Deliberadamente NO se tocan {@code REJECTED}, {@code UNAVAILABLE} ni
   * los estados de prospección ({@code CONTACT_PENDING}, {@code CONTACTED},
   * {@code RESPONDED}): hoy nada en el código los escribe —solo el
   * {@code PATCH} de ops— y decidir si cada uno corta el trabajo es de Carlos,
   * no de un fix nocturno.
   */
  private boolean availableForNewWork(Provider provider, Set<Long> overdueProviderIds) {
    return availableForNewWork(provider, overdueProviderIds.contains(provider.getId()));
  }

  /**
   * Variante por proveedor único (resuelve la comisión vencida con su propio
   * query). Para listas usar la sobrecarga con el set batcheado: evita un
   * query por proveedor dentro del stream.
   */
  public boolean canReceiveNewWork(Provider provider) {
    boolean commissionOverdue = leadPaymentRepository
        .findByProviderIdOrderByCreatedAtDesc(provider.getId()).stream()
        .anyMatch(payment -> payment.getCommissionStatus() == CommissionStatus.OVERDUE);
    return availableForNewWork(provider, commissionOverdue);
  }

  /** Igual que {@link #canReceiveNewWork(Provider)} pero con el set de
   * comisiones vencidas ya resuelto, para recorrer el padrón entero. */
  public boolean canReceiveNewWork(Provider provider, Set<Long> overdueProviderIds) {
    return availableForNewWork(provider, overdueProviderIds);
  }

  /** Un solo query con los providerIds que tienen comisión vencida. */
  public Set<Long> overdueProviderIds() {
    return leadPaymentRepository.findProviderIdsByCommissionStatus(CommissionStatus.OVERDUE);
  }

  private boolean availableForNewWork(Provider provider, boolean commissionOverdue) {
    return provider.getStatus() != ProviderStatus.BLOCKED
        && provider.getStatus() != ProviderStatus.INACTIVE
        && provider.getStatus() != ProviderStatus.NEW
        && !isPaused(provider)
        && !commissionOverdue;
  }

  /** En pausa por decisión del propio proveedor. {@code null} es el default
   * histórico de la columna y significa disponible ({@link Provider}). */
  private boolean isPaused(Provider provider) {
    return provider.getAcceptingWork() != null && !provider.getAcceptingWork();
  }

  private boolean matchesCategory(Provider provider, String category) {
    if (category.isBlank()) {
      return true;
    }
    return splitCsv(provider.getCategories()).stream()
        .map(this::normalize)
        .anyMatch(value -> value.equals(category));
  }

  /**
   * ¿Este proveedor cubre la zona del pedido? Compara lo que declaró como
   * cobertura ({@code primaryZone} y {@code coverageZones}) con
   * {@link CoverageZone#covers}, que entiende que "Ciudad de la Costa"
   * contiene a Solymar, Lagomar y compañía en vez de tratarlas como strings
   * sueltos e iguales entre sí. {@code city} queda aparte, en igualdad exacta.
   *
   * <p>Antes la comparación era igualdad exacta, y eso dejaba fuera dos casos
   * reales del embudo de prod (detalle y números en el javadoc de
   * {@code CoverageZone.covers}): el proveedor que declara la ciudad entera no
   * veía los pedidos de cada barrio, y el 26% de los pedidos que llegan con la
   * zona genérica "Ciudad de la Costa" no veía a ningún proveedor registrado
   * por barrio.
   */
  private boolean matchesLocation(Provider provider, String location) {
    if (location.isBlank()) {
      return true;
    }

    if (declaredCoverage(provider).stream().anyMatch(zone -> CoverageZone.covers(zone, location))) {
      return true;
    }

    // city NO entra en la jerarquía a propósito: es la dirección del
    // proveedor, no una declaración de cobertura. Expandirla haría que un
    // proveedor que acotó su cobertura a "Solymar, Lagomar" recibiera pedidos
    // de El Pinar solo porque su ciudad postal es Ciudad de la Costa — lo
    // contrario de lo que eligió. Queda como igualdad exacta, como estaba.
    return normalize(provider.getCity()).equals(location);
  }

  /**
   * true si el proveedor nombró esta zona puntualmente (no la alcanzó por el
   * paraguas). Solo ordena — no filtra a nadie.
   */
  private boolean declaresZoneExactly(Provider provider, String location) {
    if (location.isBlank()) {
      return false;
    }
    return declaredCoverage(provider).stream().anyMatch(zone -> normalize(zone).equals(location));
  }

  /**
   * Lo que el proveedor declaró como cobertura: {@code primaryZone} y
   * {@code coverageZones}. Son las dos que expresan "hasta acá llego", así que
   * son las únicas donde vale la jerarquía Ciudad de la Costa ⊃ barrios.
   */
  private List<String> declaredCoverage(Provider provider) {
    List<String> zones = new java.util.ArrayList<>();
    zones.add(provider.getPrimaryZone());
    zones.addAll(splitCsv(provider.getCoverageZones()));
    return zones.stream().filter(Objects::nonNull).filter(value -> !value.isBlank()).toList();
  }

  private Provider findProvider(Long id) {
    return providerRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found"));
  }

  private ProviderCatalogItem toCatalogItem(Provider provider) {
    return toCatalogItem(provider, "");
  }

  private ProviderCatalogItem toCatalogItem(Provider provider, String matchedCategory) {
    String primaryCategory = splitCsv(provider.getCategories()).stream().findFirst().orElse("");
    String displayCategory = splitCsv(provider.getCategories()).stream()
        .filter(category -> normalize(category).equals(matchedCategory))
        .findFirst()
        .orElse(primaryCategory);
    String zone = firstNonBlank(provider.getPrimaryZone(), provider.getCity());
    return new ProviderCatalogItem(
        provider.getId(),
        provider.getName(),
        displayCategory,
        zone,
        firstNonBlank(provider.getWhatsappNumber(), provider.getPhone()),
        provider.getStatus().name(),
        provider.getSourceType()
    );
  }

  private ProviderResponse toResponse(Provider provider) {
    return new ProviderResponse(
        provider.getId(),
        provider.getName(),
        provider.getPhone(),
        provider.getWhatsappNumber(),
        provider.getSourceName(),
        provider.getSourceType(),
        provider.getPrimaryZone(),
        splitCsv(provider.getCoverageZones()),
        provider.getCity(),
        provider.getDepartment(),
        splitCsv(provider.getCategories()),
        provider.getCategoryNotes(),
        provider.getStatus(),
        provider.getVerificationStatus(),
        provider.getRatingAverage(),
        provider.getRatingCount(),
        provider.getInternalScore(),
        splitCsv(provider.getRiskFlags()),
        provider.getLastContactedAt(),
        provider.getLastRespondedAt(),
        provider.getAcceptedJobsCount(),
        provider.getRejectedJobsCount(),
        provider.getCompletedJobsCount(),
        provider.getNotes(),
        provider.getCreatedAt(),
        provider.getUpdatedAt()
    );
  }

  private String normalize(String value) {
    if (value == null) {
      return "";
    }
    // Sin acentos: "Shangrilá" (cliente) tiene que matchear "Shangrila"
    // (proveedor registrado). Mismo tipo de bug que el de categorías por
    // etiqueta humana — descubierto en la prueba de push con Melissa.
    String lowered = value.trim().toLowerCase(Locale.ROOT);
    return java.text.Normalizer.normalize(lowered, java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "");
  }

  private List<String> splitCsv(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList();
  }

  private String normalizeCsv(String raw) {
    return String.join(", ", splitCsv(raw));
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

  private String firstNonBlank(String first, String second) {
    return Objects.requireNonNullElseGet(trimToNull(first), () -> Objects.requireNonNullElse(trimToNull(second), ""));
  }
}
