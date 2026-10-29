package com.fixy.backend.service;

import com.fixy.backend.dto.BarrioDtos.AiAgreement;
import com.fixy.backend.dto.BarrioDtos.ApproveResponse;
import com.fixy.backend.dto.BarrioDtos.CategoryCount;
import com.fixy.backend.dto.BarrioDtos.ClosureResponse;
import com.fixy.backend.dto.BarrioDtos.ClosureReviewResponse;
import com.fixy.backend.dto.BarrioDtos.CreateResponse;
import com.fixy.backend.dto.BarrioDtos.OpsAi;
import com.fixy.backend.dto.BarrioDtos.ClosureAgreement;
import com.fixy.backend.dto.BarrioDtos.OpsClosure;
import com.fixy.backend.dto.BarrioDtos.OpsClosureAi;
import com.fixy.backend.dto.BarrioDtos.OpsClosureEvidence;
import com.fixy.backend.dto.BarrioDtos.OpsPending;
import com.fixy.backend.dto.BarrioDtos.OpsReport;
import com.fixy.backend.dto.BarrioDtos.PublicReport;
import com.fixy.backend.dto.BarrioDtos.Stats;
import com.fixy.backend.dto.BarrioDtos.ZoneCount;
import com.fixy.backend.model.BarrioClosureClaim;
import com.fixy.backend.model.BarrioReport;
import com.fixy.backend.repository.BarrioClosureClaimRepository;
import com.fixy.backend.repository.BarrioReportRepository;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fixy Barrio B1 (BARRIO_B1_CONTRATO.md): alta de reclamos (web y WhatsApp), moderación,
 * dedupe ≤ 40 m al aprobar, cierres con foto, y las vistas pública / de ops. Tablas propias,
 * nada de {@code leads}. Sin mensajes salientes: Telegram avisa a Carlos y nada más.
 */
@Service
public class BarrioService {

  private static final Logger log = LoggerFactory.getLogger(BarrioService.class);

  public static final double DEDUPE_METERS = 40.0;
  static final int DESCRIPTION_MAX = 500;
  static final int REJECT_REASON_MAX = 300;
  static final Set<String> CATEGORIES = Set.of(
      BarrioTriggers.BACHE, BarrioTriggers.PODA, BarrioTriggers.LUMINARIA);
  private static final Map<String, String> LABELS = Map.of(
      BarrioTriggers.BACHE, "Bache",
      BarrioTriggers.PODA, "Restos de poda",
      BarrioTriggers.LUMINARIA, "Luminaria apagada");

  private final BarrioReportRepository reports;
  private final BarrioClosureClaimRepository closures;
  private final BarrioPhotoService photos;
  private final TelegramNotifyService telegram;
  /** null = sin IA revisora (constructor legacy de los tests): el aviso sale enseguida como siempre. */
  private final BarrioAiReviewService aiReview;
  /** null = sin IA de cierres (constructores legacy de los tests): el aviso del cierre sale enseguida. */
  private final BarrioClosureReviewService closureReview;
  private final int maxWritesPerWindow;
  private final Duration window;
  private final Map<String, Deque<Instant>> writesByIp = new ConcurrentHashMap<>();

  /** Constructor legacy (6 args, tests): sin IA revisora. */
  public BarrioService(
      BarrioReportRepository reports,
      BarrioClosureClaimRepository closures,
      BarrioPhotoService photos,
      TelegramNotifyService telegram,
      int maxWritesPerWindow,
      long windowSeconds
  ) {
    this(reports, closures, photos, telegram, null, null, maxWritesPerWindow, windowSeconds);
  }

  /** Constructor legacy (7 args): sin IA de cierres. */
  public BarrioService(
      BarrioReportRepository reports,
      BarrioClosureClaimRepository closures,
      BarrioPhotoService photos,
      TelegramNotifyService telegram,
      BarrioAiReviewService aiReview,
      int maxWritesPerWindow,
      long windowSeconds
  ) {
    this(reports, closures, photos, telegram, aiReview, null, maxWritesPerWindow, windowSeconds);
  }

  @Autowired
  public BarrioService(
      BarrioReportRepository reports,
      BarrioClosureClaimRepository closures,
      BarrioPhotoService photos,
      TelegramNotifyService telegram,
      BarrioAiReviewService aiReview,
      BarrioClosureReviewService closureReview,
      @Value("${fixy.barrio.rate-limit.max-requests-per-window:10}") int maxWritesPerWindow,
      @Value("${fixy.barrio.rate-limit.window-seconds:600}") long windowSeconds
  ) {
    this.aiReview = aiReview;
    this.closureReview = closureReview;
    this.reports = reports;
    this.closures = closures;
    this.photos = photos;
    this.telegram = telegram;
    this.maxWritesPerWindow = maxWritesPerWindow;
    this.window = Duration.ofSeconds(windowSeconds);
  }

  public static String labelOf(String category) {
    return LABELS.getOrDefault(category, category);
  }

  // ------------------------------------------------------------------ alta

  /** Alta web (multipart). Valida, chequea cobertura, procesa la foto y deja PENDING_REVIEW. */
  @Transactional
  public CreateResponse createFromWeb(
      String clientIp, String category, Double lat, Double lng, MultipartFile photo,
      String description, String phone
  ) {
    enforceRateLimit(clientIp);
    String cat = requireCategory(category);
    requireCoordinates(lat, lng);
    String desc = cleanDescription(description);
    String normalizedPhone = cleanPhone(phone);
    if (photo == null || photo.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "la foto es obligatoria");
    }
    if (!BarrioGeo.inCoverage(lat, lng)) {
      throw BarrioApiException.fueraDeZona();
    }
    String photoPath = photos.store(photo);
    return create(cat, lat, lng, photoPath, desc, normalizedPhone, "web");
  }

  /** Alta ya validada (la foto ya está guardada): usada por la web y por el borrador de WhatsApp. */
  @Transactional
  public CreateResponse create(
      String category, double lat, double lng, String photoPath, String description,
      String reporterPhone, String channel
  ) {
    OffsetDateTime now = OffsetDateTime.now();
    BarrioReport report = new BarrioReport();
    report.setCategory(category);
    report.setStatus(BarrioReport.PENDING_REVIEW);
    report.setLat(lat);
    report.setLng(lng);
    report.setZone(BarrioGeo.zoneFor(lat, lng));
    report.setPhotoPath(photoPath);
    report.setDescription(description);
    report.setReporterPhone(reporterPhone);
    report.setChannel(channel);
    report.setCreatedAt(now);
    report.setUpdatedAt(now);
    // IA revisora: si va a revisar, el aviso a Telegram sale DESPUÉS de la revisión (la manda el
    // scheduler, o al agotar intentos). Si no hay IA (apagada / sin key) → SIN_IA y aviso enseguida.
    boolean aiWillReview = aiReview != null && aiReview.isActive();
    if (aiReview != null && !aiWillReview) {
      report.setAiVerdict(BarrioAiVerdict.SIN_IA);
    }
    BarrioReport saved = reports.save(report);
    Long dup = findDuplicate(saved).map(BarrioReport::getId).orElse(null);
    if (!aiWillReview) {
      try {
        if (aiReview != null) {
          aiReview.notifyTelegram(saved);
        } else {
          telegram.notifyBarrioReport(saved.getId(), labelOf(category), saved.getZone(), description);
        }
      } catch (RuntimeException e) {
        log.warn("telegram barrio report #{} falló: {}", saved.getId(), e.getMessage());
      }
    }
    return new CreateResponse(saved.getId(), saved.getStatus(), dup);
  }

  // ------------------------------------------------------------- público

  @Transactional(readOnly = true)
  public List<PublicReport> listPublic(String filter) {
    String f = filter == null || filter.isBlank() ? "open" : filter.trim().toLowerCase();
    List<String> statuses = switch (f) {
      case "open" -> List.of(BarrioReport.PUBLISHED);
      case "resolved" -> List.of(BarrioReport.RESOLVED);
      case "all" -> List.of(BarrioReport.PUBLISHED, BarrioReport.RESOLVED);
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status debe ser open, resolved o all");
    };
    OffsetDateTime now = OffsetDateTime.now();
    return reports.findByStatusInOrderByCreatedAtAsc(statuses).stream()
        .map(r -> toPublic(r, now))
        .toList();
  }

  /** Detalle público. Un MERGED devuelve el reporte destino (el {@code id} del objeto es el del destino). */
  @Transactional(readOnly = true)
  public PublicReport getPublic(Long id) {
    BarrioReport report = reports.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "reclamo no encontrado"));
    for (int hops = 0; BarrioReport.MERGED.equals(report.getStatus()) && report.getMergedIntoId() != null && hops < 5; hops++) {
      report = reports.findById(report.getMergedIntoId())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "reclamo no encontrado"));
    }
    if (!isPublic(report)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "reclamo no encontrado");
    }
    return toPublic(report, OffsetDateTime.now());
  }

  @Transactional(readOnly = true)
  public Stats stats() {
    OffsetDateTime now = OffsetDateTime.now();
    List<BarrioReport> all = reports.findByStatusInOrderByCreatedAtAsc(
        List.of(BarrioReport.PUBLISHED, BarrioReport.RESOLVED));
    Map<String, long[]> byCategory = new LinkedHashMap<>();
    for (String c : List.of(BarrioTriggers.BACHE, BarrioTriggers.PODA, BarrioTriggers.LUMINARIA)) {
      byCategory.put(c, new long[2]);
    }
    Map<String, long[]> byZone = new LinkedHashMap<>();
    long open = 0;
    long resolved = 0;
    long daysSum = 0;
    for (BarrioReport r : all) {
      boolean isOpen = BarrioReport.PUBLISHED.equals(r.getStatus());
      int idx = isOpen ? 0 : 1;
      if (isOpen) {
        open++;
        daysSum += daysOpen(r, now);
      } else {
        resolved++;
      }
      byCategory.computeIfAbsent(r.getCategory(), k -> new long[2])[idx]++;
      byZone.computeIfAbsent(r.getZone(), k -> new long[2])[idx]++;
    }
    double avg = open == 0 ? 0.0 : Math.round(daysSum * 10.0 / open) / 10.0;
    Map<String, CategoryCount> cats = new LinkedHashMap<>();
    byCategory.forEach((k, v) -> cats.put(k, new CategoryCount(v[0], v[1])));
    List<ZoneCount> zones = new ArrayList<>();
    byZone.forEach((k, v) -> zones.add(new ZoneCount(k, v[0], v[1])));
    zones.sort(Comparator.comparingLong(ZoneCount::open).reversed().thenComparing(ZoneCount::zone));
    return new Stats(open, resolved, avg, cats, zones);
  }

  /** Cierre sin ubicación (compat): equivale a {@code sin_ubicacion}. */
  @Transactional
  public ClosureResponse submitClosure(String clientIp, Long reportId, MultipartFile photo, String comment) {
    return submitClosure(clientIp, reportId, photo, comment, null, null, null);
  }

  /**
   * Cierre ("ya lo arreglaron"): foto obligatoria, solo sobre PUBLISHED, queda para que ops lo apruebe.
   * Junta pruebas independientes (AVISA_CIERRE_C1_CONTRATO.md): distancia al reclamo desde la ubicación
   * del celular, fecha EXIF de la foto original y huella dHash contra el reclamo y otros cierres. La
   * ubicación es opcional (inválida o ausente = {@code sin_ubicacion}) y nunca se expone en la API pública.
   */
  @Transactional
  public ClosureResponse submitClosure(
      String clientIp, Long reportId, MultipartFile photo, String comment,
      Double lat, Double lng, Double accuracy
  ) {
    enforceRateLimit(clientIp);
    BarrioReport report = reports.findById(reportId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "reclamo no encontrado"));
    if (!BarrioReport.PUBLISHED.equals(report.getStatus())) {
      throw new BarrioApiException(HttpStatus.CONFLICT, "no_publicado",
          "solo se puede cerrar un reclamo abierto");
    }
    if (photo == null || photo.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "la foto es obligatoria");
    }
    String cleanComment = cleanDescription(comment);
    BarrioPhotoService.StoredPhoto stored = photos.storeWithMeta(photo);
    BarrioClosureClaim claim = new BarrioClosureClaim();
    claim.setReportId(reportId);
    claim.setPhotoPath(stored.path());
    claim.setComment(cleanComment);
    claim.setStatus(BarrioClosureClaim.PENDING_REVIEW);
    claim.setCreatedAt(OffsetDateTime.now());
    claim.setPhotoTakenAt(stored.takenAt());
    claim.setPhotoDhash(stored.dhash());
    if (validLocation(lat, lng)) {
      claim.setLat(lat);
      claim.setLng(lng);
      claim.setDistanceM(BarrioGeo.haversineMeters(lat, lng, report.getLat(), report.getLng()));
      if (accuracy != null && Double.isFinite(accuracy) && accuracy >= 0) {
        claim.setAccuracyM(accuracy);
      }
    }
    claim.setDhashMinDistance(minDhashDistance(report, reportId, stored.dhash()));
    boolean aiWillReview = closureReview != null && closureReview.isActive();
    if (closureReview != null && !aiWillReview) {
      claim.setAiVerdict(BarrioClosureReviewService.SIN_IA);
    }
    BarrioClosureEvidence.Result evidence = BarrioClosureEvidence.evaluate(
        BarrioClosureReviewService.factsOf(claim, report), claim.getAiVerdict(), null, 0,
        BarrioClosureReviewService.MAX_ATTEMPTS);
    claim.setEvidenceVerdict(evidence.verdict());
    claim.setEvidenceReasons(String.join(",", evidence.reasons()));
    closures.save(claim);
    // Con IA, el aviso sale DESPUÉS de la revisión (scheduler, o al agotar intentos).
    if (!aiWillReview) {
      try {
        if (closureReview != null) {
          closureReview.notifyTelegram(claim, report);
        } else {
          telegram.notifyBarrioClosure(reportId, labelOf(report.getCategory()), report.getZone(), cleanComment);
        }
      } catch (RuntimeException e) {
        log.warn("telegram barrio cierre #{} falló: {}", reportId, e.getMessage());
      }
    }
    return new ClosureResponse(BarrioClosureClaim.PENDING_REVIEW);
  }

  private static boolean validLocation(Double lat, Double lng) {
    return lat != null && lng != null && Double.isFinite(lat) && Double.isFinite(lng)
        && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
  }

  /** Hamming mínima de la foto nueva contra la del reclamo y contra los otros cierres del mismo reclamo. */
  private Integer minDhashDistance(BarrioReport report, Long reportId, long dhash) {
    Integer min = null;
    try {
      int d = Long.bitCount(dhash ^ BarrioImageHash.dhash(photos.read(report.getPhotoPath())));
      min = d;
    } catch (RuntimeException e) {
      log.warn("barrio cierre: no se pudo calcular el dHash del reclamo #{}: {}", reportId, e.getMessage());
    }
    for (BarrioClosureClaim other : closures.findByReportId(reportId)) {
      if (other.getPhotoDhash() == null) continue;
      int d = Long.bitCount(dhash ^ other.getPhotoDhash());
      if (min == null || d < min) min = d;
    }
    return min;
  }

  // ----------------------------------------------------------------- ops

  @Transactional(readOnly = true)
  public OpsPending pending() {
    OffsetDateTime now = OffsetDateTime.now();
    List<OpsReport> pendingReports = reports.findByStatusOrderByCreatedAtAsc(BarrioReport.PENDING_REVIEW).stream()
        .map(r -> toOps(r, now))
        .toList();
    List<OpsClosure> pendingClosures = closures.findByStatusOrderByCreatedAtAsc(BarrioClosureClaim.PENDING_REVIEW).stream()
        .map(c -> {
          OpsReport rep = reports.findById(c.getReportId()).map(r -> toOps(r, now)).orElse(null);
          return new OpsClosure(c.getId(), c.getReportId(), photos.urlFor(c.getPhotoPath()),
              c.getComment(), c.getCreatedAt(), rep, rep == null ? null : rep.photoUrl(), toEvidence(c));
        })
        .toList();
    return new OpsPending(pendingReports, pendingClosures);
  }

  /** Aprueba un reclamo; si hay uno PUBLISHED de la misma categoría a ≤ 40 m, se funde en él (MERGED). */
  @Transactional
  public ApproveResponse approve(Long id) {
    BarrioReport report = requirePending(id);
    OffsetDateTime now = OffsetDateTime.now();
    applyApproval(report, now);
    report.setHumanDecision(BarrioReport.MERGED.equals(report.getStatus()) ? "MERGED" : "APPROVED");
    report.setHumanDecidedAt(now);
    report.setUpdatedAt(now);
    reports.save(report);
    return new ApproveResponse(report.getStatus(), report.getMergedIntoId());
  }

  /**
   * Auto-publicación de la IA (Fase 2): MISMA lógica que approve (dedupe incluido) pero sin
   * human_*. Relee y revalida PENDING_REVIEW dentro de la transacción; devuelve empty si ya no lo está.
   */
  @Transactional
  public Optional<ApproveResponse> autoPublish(Long id) {
    BarrioReport report = reports.findById(id).orElse(null);
    if (report == null || !BarrioReport.PENDING_REVIEW.equals(report.getStatus())) {
      return Optional.empty();
    }
    OffsetDateTime now = OffsetDateTime.now();
    applyApproval(report, now);
    report.setAutoPublishedAt(now);
    report.setUpdatedAt(now);
    reports.save(report);
    return Optional.of(new ApproveResponse(report.getStatus(), report.getMergedIntoId()));
  }

  /** Núcleo común de approve / autoPublish: PUBLISHED, o MERGED en un publicado a ≤ 40 m. */
  private void applyApproval(BarrioReport report, OffsetDateTime now) {
    Optional<BarrioReport> dup = findDuplicate(report);
    if (dup.isPresent()) {
      BarrioReport target = dup.get();
      report.setStatus(BarrioReport.MERGED);
      report.setMergedIntoId(target.getId());
      target.setSupporters(target.getSupporters() + Math.max(1, report.getSupporters()));
      // Los días abiertos cuentan desde el reporte más viejo.
      if (report.getCreatedAt().isBefore(target.getCreatedAt())) {
        target.setCreatedAt(report.getCreatedAt());
      }
      target.setUpdatedAt(now);
      reports.save(target);
    } else {
      report.setStatus(BarrioReport.PUBLISHED);
      report.setPublishedAt(now);
    }
  }

  /** Auto-publicados de hoy (día de Montevideo), para el tope diario. */
  public long publishedTodayByAi() {
    java.time.Instant now = java.time.Instant.now();
    return reports.countByAutoPublishedAtGreaterThanEqualAndAutoPublishedAtLessThan(
        OffsetDateTime.ofInstant(BarrioAutoPublishPolicy.dayStart(now), BarrioAutoPublishPolicy.ZONE),
        OffsetDateTime.ofInstant(BarrioAutoPublishPolicy.dayEnd(now), BarrioAutoPublishPolicy.ZONE));
  }

  @Transactional(readOnly = true)
  public List<OpsReport> autoPublished() {
    OffsetDateTime now = OffsetDateTime.now();
    return reports.findAutoPublishedUnreviewed().stream().map(r -> toOps(r, now)).toList();
  }

  /** Carlos revisó un auto-publicado y está bien. 409 si no fue auto-publicado o ya se decidió. */
  @Transactional
  public ApproveResponse confirm(Long id) {
    BarrioReport report = reports.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "reclamo no encontrado"));
    if (report.getAutoPublishedAt() == null || report.getHumanDecision() != null) {
      throw new BarrioApiException(HttpStatus.CONFLICT, "estado_invalido", "el reclamo no está esperando confirmación");
    }
    OffsetDateTime now = OffsetDateTime.now();
    report.setHumanDecision(BarrioReport.MERGED.equals(report.getStatus()) ? "MERGED" : "APPROVED");
    report.setHumanDecidedAt(now);
    report.setUpdatedAt(now);
    reports.save(report);
    return new ApproveResponse(report.getStatus(), report.getMergedIntoId());
  }

  /**
   * Saca de la vista pública un reclamo PUBLISHED (o MERGED auto-publicado) y lo deja REJECTED. Si era
   * MERGED resta lo aportado al destino (mínimo 1); el created_at del destino NO se revierte.
   */
  @Transactional
  public ApproveResponse unpublish(Long id, String reason) {
    BarrioReport report = reports.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "reclamo no encontrado"));
    boolean published = BarrioReport.PUBLISHED.equals(report.getStatus());
    boolean autoMerged = BarrioReport.MERGED.equals(report.getStatus()) && report.getAutoPublishedAt() != null;
    if (!published && !autoMerged) {
      throw new BarrioApiException(HttpStatus.CONFLICT, "estado_invalido", "el reclamo no se puede despublicar");
    }
    String clean = reason == null ? null : reason.trim();
    if (clean != null && clean.length() > REJECT_REASON_MAX) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason demasiado largo");
    }
    OffsetDateTime now = OffsetDateTime.now();
    if (autoMerged && report.getMergedIntoId() != null) {
      reports.findById(report.getMergedIntoId()).ifPresent(target -> {
        target.setSupporters(Math.max(1, target.getSupporters() - Math.max(1, report.getSupporters())));
        target.setUpdatedAt(now);
        reports.save(target);
      });
    }
    report.setStatus(BarrioReport.REJECTED);
    report.setRejectReason(clean == null || clean.isEmpty() ? null : clean);
    report.setHumanDecision("REJECTED");
    report.setHumanDecidedAt(now);
    report.setUpdatedAt(now);
    reports.save(report);
    return new ApproveResponse(report.getStatus(), null);
  }

  @Transactional
  public ApproveResponse reject(Long id, String reason) {
    BarrioReport report = requirePending(id);
    String clean = reason == null ? null : reason.trim();
    if (clean != null && clean.length() > REJECT_REASON_MAX) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason demasiado largo");
    }
    report.setStatus(BarrioReport.REJECTED);
    report.setRejectReason(clean == null || clean.isEmpty() ? null : clean);
    report.setHumanDecision("REJECTED");
    report.setHumanDecidedAt(OffsetDateTime.now());
    report.setUpdatedAt(OffsetDateTime.now());
    reports.save(report);
    return new ApproveResponse(report.getStatus(), null);
  }

  /** Corrección de ops antes (o después) de publicar: categoría y/o ubicación; recalcula el barrio. */
  @Transactional
  public OpsReport patch(Long id, String category, Double lat, Double lng) {
    BarrioReport report = reports.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "reclamo no encontrado"));
    if (!BarrioReport.PENDING_REVIEW.equals(report.getStatus()) && !BarrioReport.PUBLISHED.equals(report.getStatus())) {
      throw new BarrioApiException(HttpStatus.CONFLICT, "estado_invalido", "el reclamo ya no se puede editar");
    }
    if (category != null) {
      report.setCategory(requireCategory(category));
    }
    if (lat != null || lng != null) {
      double newLat = lat != null ? lat : report.getLat();
      double newLng = lng != null ? lng : report.getLng();
      requireCoordinates(newLat, newLng);
      if (!BarrioGeo.inCoverage(newLat, newLng)) {
        throw BarrioApiException.fueraDeZona();
      }
      report.setLat(newLat);
      report.setLng(newLng);
      report.setZone(BarrioGeo.zoneFor(newLat, newLng));
    }
    report.setUpdatedAt(OffsetDateTime.now());
    reports.save(report);
    return toOps(report, OffsetDateTime.now());
  }

  @Transactional
  public ClosureReviewResponse approveClosure(Long claimId) {
    BarrioClosureClaim claim = requirePendingClosure(claimId);
    BarrioReport report = reports.findById(claim.getReportId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "reclamo no encontrado"));
    if (!BarrioReport.PUBLISHED.equals(report.getStatus())) {
      throw new BarrioApiException(HttpStatus.CONFLICT, "estado_invalido", "el reclamo ya no está abierto");
    }
    OffsetDateTime now = OffsetDateTime.now();
    claim.setStatus(BarrioClosureClaim.APPROVED);
    claim.setReviewedAt(now);
    claim.setHumanDecision("APPROVED");
    claim.setHumanDecidedAt(now);
    closures.save(claim);
    report.setStatus(BarrioReport.RESOLVED);
    report.setResolvedAt(now);
    report.setClosurePhotoPath(claim.getPhotoPath());
    report.setDaysToResolve((int) Math.max(0, ChronoUnit.DAYS.between(report.getCreatedAt(), now)));
    report.setUpdatedAt(now);
    reports.save(report);
    // Otros cierres pendientes del mismo reclamo ya no tienen sentido.
    for (BarrioClosureClaim other : closures.findByReportIdAndStatus(report.getId(), BarrioClosureClaim.PENDING_REVIEW)) {
      other.setStatus(BarrioClosureClaim.REJECTED);
      other.setReviewedAt(now);
      closures.save(other);
    }
    return new ClosureReviewResponse(BarrioClosureClaim.APPROVED, report.getId());
  }

  @Transactional
  public ClosureReviewResponse rejectClosure(Long claimId) {
    BarrioClosureClaim claim = requirePendingClosure(claimId);
    OffsetDateTime now = OffsetDateTime.now();
    claim.setStatus(BarrioClosureClaim.REJECTED);
    claim.setReviewedAt(now);
    claim.setHumanDecision("REJECTED");
    claim.setHumanDecidedAt(now);
    closures.save(claim);
    return new ClosureReviewResponse(BarrioClosureClaim.REJECTED, claim.getReportId());
  }

  // ------------------------------------------------------------- helpers

  /** Reporte PUBLISHED de la misma categoría más cercano a ≤ 40 m (excluye al propio). */
  Optional<BarrioReport> findDuplicate(BarrioReport report) {
    return nearestWithin(
        reports.findByStatusAndCategory(BarrioReport.PUBLISHED, report.getCategory()),
        report.getLat(), report.getLng(), DEDUPE_METERS, report.getId());
  }

  /** El candidato más cercano a (lat, lng) dentro de {@code meters}, ignorando {@code excludeId}. */
  static Optional<BarrioReport> nearestWithin(
      List<BarrioReport> candidates, double lat, double lng, double meters, Long excludeId
  ) {
    BarrioReport best = null;
    double bestMeters = Double.MAX_VALUE;
    for (BarrioReport c : candidates) {
      if (excludeId != null && excludeId.equals(c.getId())) continue;
      double d = BarrioGeo.haversineMeters(lat, lng, c.getLat(), c.getLng());
      if (d <= meters && d < bestMeters) {
        best = c;
        bestMeters = d;
      }
    }
    return Optional.ofNullable(best);
  }

  private BarrioReport requirePending(Long id) {
    BarrioReport report = reports.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "reclamo no encontrado"));
    if (!BarrioReport.PENDING_REVIEW.equals(report.getStatus())) {
      throw new BarrioApiException(HttpStatus.CONFLICT, "estado_invalido", "el reclamo no está pendiente de revisión");
    }
    return report;
  }

  private BarrioClosureClaim requirePendingClosure(Long id) {
    BarrioClosureClaim claim = closures.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "cierre no encontrado"));
    if (!BarrioClosureClaim.PENDING_REVIEW.equals(claim.getStatus())) {
      throw new BarrioApiException(HttpStatus.CONFLICT, "estado_invalido", "el cierre no está pendiente de revisión");
    }
    return claim;
  }

  private static boolean isPublic(BarrioReport r) {
    return BarrioReport.PUBLISHED.equals(r.getStatus()) || BarrioReport.RESOLVED.equals(r.getStatus());
  }

  static String requireCategory(String category) {
    String c = category == null ? "" : category.trim().toLowerCase();
    if (!CATEGORIES.contains(c)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category debe ser bache, poda o luminaria");
    }
    return c;
  }

  private static void requireCoordinates(Double lat, Double lng) {
    if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)
        || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lat y lng son obligatorios");
    }
  }

  private static String cleanDescription(String description) {
    if (description == null || description.isBlank()) return null;
    String d = description.trim();
    if (d.length() > DESCRIPTION_MAX) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "el texto no puede pasar de %d caracteres".formatted(DESCRIPTION_MAX));
    }
    return d;
  }

  private static String cleanPhone(String phone) {
    if (phone == null || phone.isBlank()) return null;
    String normalized = WhatsAppService.normalize(phone);
    if (normalized == null || normalized.length() < 10 || normalized.length() > 15) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "telefono invalido");
    }
    return normalized;
  }

  private static long daysOpen(BarrioReport r, OffsetDateTime now) {
    OffsetDateTime end = BarrioReport.RESOLVED.equals(r.getStatus()) && r.getResolvedAt() != null ? r.getResolvedAt() : now;
    return Math.max(0, ChronoUnit.DAYS.between(r.getCreatedAt(), end));
  }

  private static double round4(double v) {
    return Math.round(v * 10_000.0) / 10_000.0;
  }

  PublicReport toPublic(BarrioReport r, OffsetDateTime now) {
    return new PublicReport(
        r.getId(), r.getCategory(), labelOf(r.getCategory()), r.getZone(),
        round4(r.getLat()), round4(r.getLng()), photos.urlFor(r.getPhotoPath()),
        r.getCreatedAt(), daysOpen(r, now), r.getSupporters(), r.getStatus(),
        r.getResolvedAt(), r.getDaysToResolve(), photos.urlFor(r.getClosurePhotoPath()));
  }

  OpsReport toOps(BarrioReport r, OffsetDateTime now) {
    Long dup = BarrioReport.PENDING_REVIEW.equals(r.getStatus())
        ? findDuplicate(r).map(BarrioReport::getId).orElse(null) : null;
    return new OpsReport(
        r.getId(), r.getCategory(), labelOf(r.getCategory()), r.getZone(),
        r.getLat(), r.getLng(), photos.urlFor(r.getPhotoPath()), r.getCreatedAt(),
        daysOpen(r, now), r.getSupporters(), r.getStatus(), r.getDescription(),
        maskPhone(r.getReporterPhone()), r.getChannel(), dup, toAi(r),
        r.getAutoPublishedAt(), r.getMergedIntoId());
  }

  /**
   * Opinión de la IA para ops. null = "revisando": aún sin veredicto, o ERROR con intentos por
   * delante (el scheduler reintenta). ERROR con intentos agotados sí se expone ("no pudo revisar").
   */
  static OpsAi toAi(BarrioReport r) {
    String v = r.getAiVerdict();
    if (v == null) return null;
    if (BarrioAiVerdict.ERROR.equals(v) && r.getAiAttempts() < BarrioAiReviewService.MAX_ATTEMPTS) return null;
    return new OpsAi(v, r.getAiConfidence(), BarrioAiReviewService.splitReasons(r.getAiReasons()),
        r.getAiSuggestedCategory(), r.getAiSummary(), r.getAiReviewedAt());
  }

  /** Pruebas del cierre para ops. {@code ai} es null mientras la IA revisa (sin veredicto, o ERROR con intentos por delante). */
  static OpsClosureEvidence toEvidence(BarrioClosureClaim c) {
    String v = c.getAiVerdict();
    OpsClosureAi ai = null;
    if (v != null && !(BarrioClosureReviewService.ERROR.equals(v) && c.getAiAttempts() < BarrioClosureReviewService.MAX_ATTEMPTS)) {
      ai = new OpsClosureAi(v, c.getAiSamePlace(), c.getAiIssueGone(), c.getAiReused(), c.getAiConfidence(),
          c.getAiSummary(), c.getAiReviewedAt());
    }
    return new OpsClosureEvidence(
        c.getEvidenceVerdict() == null ? BarrioClosureEvidence.PENDIENTE : c.getEvidenceVerdict(),
        BarrioAiReviewService.splitReasons(c.getEvidenceReasons()), c.getDistanceM(), c.getAccuracyM(),
        c.getPhotoTakenAt(), c.getDhashMinDistance(), ai);
  }

  private ClosureAgreement closureAgreement() {
    long strongApproved = 0, strongRejected = 0, weakApproved = 0, weakRejected = 0, undecided = 0;
    List<BarrioClosureClaim> reviewed = closures.findByEvidenceVerdictIn(
        List.of(BarrioClosureEvidence.FUERTE, BarrioClosureEvidence.DEBIL));
    for (BarrioClosureClaim c : reviewed) {
      String h = c.getHumanDecision();
      if (h == null) {
        undecided++;
        continue;
      }
      boolean approved = "APPROVED".equals(h);
      if (BarrioClosureEvidence.FUERTE.equals(c.getEvidenceVerdict())) {
        if (approved) strongApproved++; else strongRejected++;
      } else {
        if (approved) weakApproved++; else weakRejected++;
      }
    }
    long decided = strongApproved + strongRejected + weakApproved + weakRejected;
    return new ClosureAgreement(reviewed.size(), decided, strongApproved + weakRejected,
        strongApproved, strongRejected, weakApproved, weakRejected, undecided);
  }

  /** Coincidencia de la IA con la decisión humana (MERGED cuenta como aprobado). */
  @Transactional(readOnly = true)
  public AiAgreement aiAgreement() {
    long approveApproved = 0, approveRejected = 0, doubtApproved = 0, doubtRejected = 0, undecided = 0;
    List<BarrioReport> reviewed = reports.findByAiVerdictIn(
        List.of(BarrioAiVerdict.APROBAR, BarrioAiVerdict.DUDOSO));
    for (BarrioReport r : reviewed) {
      String h = r.getHumanDecision();
      if (h == null) {
        undecided++;
        continue;
      }
      boolean humanApproved = !"REJECTED".equals(h);
      if (BarrioAiVerdict.APROBAR.equals(r.getAiVerdict())) {
        if (humanApproved) approveApproved++; else approveRejected++;
      } else {
        if (humanApproved) doubtApproved++; else doubtRejected++;
      }
    }
    long autoPublished = 0, autoConfirmed = 0, autoUnpublished = 0;
    for (BarrioReport r : reports.findByAutoPublishedAtIsNotNull()) {
      autoPublished++;
      if ("REJECTED".equals(r.getHumanDecision())) autoUnpublished++;
      else if (r.getHumanDecision() != null) autoConfirmed++;
    }
    long decided = approveApproved + approveRejected + doubtApproved + doubtRejected;
    return new AiAgreement(reviewed.size(), decided, approveApproved + doubtRejected,
        approveRejected, doubtApproved, approveApproved, doubtRejected, undecided,
        autoPublished, autoConfirmed, autoUnpublished, closureAgreement());
  }

  /** Todo el teléfono menos los últimos 3 dígitos. */
  static String maskPhone(String phone) {
    if (phone == null || phone.isBlank()) return null;
    if (phone.length() <= 3) return phone;
    return "*".repeat(phone.length() - 3) + phone.substring(phone.length() - 3);
  }

  private void enforceRateLimit(String clientIp) {
    String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
    Instant now = Instant.now();
    Instant threshold = now.minus(window);
    Deque<Instant> deque = writesByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
    synchronized (deque) {
      while (!deque.isEmpty() && deque.peekFirst().isBefore(threshold)) {
        deque.pollFirst();
      }
      if (deque.size() >= maxWritesPerWindow) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many requests, retry later");
      }
      deque.addLast(now);
    }
  }
}
