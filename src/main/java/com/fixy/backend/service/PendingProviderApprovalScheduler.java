package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.model.SmokeTraffic;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Dato de prod que lo motiva (mejora diaria 2026-08-19): el proveedor #16
 * (mandados, La Costa) está en {@code NEW} desde el 2026-08-06 — 13 días —
 * mientras 11 de los 19 pedidos abiertos son justamente de mandados y el
 * chat les contesta "por ahora no tengo proveedores libres en tu zona". La
 * oferta existe, la demanda existe, y lo único que falta es un click en
 * /admin que nadie le recordó a nadie. Fixy avisa UNA vez cuando alguien se
 * autoregistra ({@link TelegramNotifyService#notifyProviderSelfRegistered})
 * y después se olvida para siempre; los cargados desde el admin (como el
 * #16) no disparan ni ese aviso.
 *
 * Esta clase convierte ese olvido en un recordatorio con el costo puesto en
 * números: cada {@code remind-every-hours} manda a ops UN digest con los
 * proveedores esperando aprobación, cuánto hace que esperan y cuántos
 * pedidos abiertos podrían tomar hoy si estuvieran activos. Lo crítico va en
 * código, no en la cabeza de nadie: el human-in-the-loop del padrón (regla
 * de captación 2026-07-22) es una dependencia de diseño, así que el sistema
 * tiene que ser el que la reclama.
 *
 * Qué cuenta como "esperando aprobación":
 * <ul>
 *   <li>{@code NEW} — cargado (admin o descubrimiento) y todavía sin triage.</li>
 *   <li>{@code INACTIVE} + {@code sourceType=autoregistro} — nace así por
 *       {@link ProviderRegistrationService}, o sea que sigue sin aprobarse.
 *       Los {@code INACTIVE} cargados a mano son bajas deliberadas y no se
 *       listan (ahí viven los proveedores de prueba viejos de prod).</li>
 * </ul>
 * De ahí sale la salida del nag: pasar al proveedor a {@code REJECTED} /
 * {@code BLOCKED} (o {@code INACTIVE} si es de autoregistro) lo saca del
 * digest tanto como activarlo. El recordatorio se apaga con una decisión,
 * nunca ignorándolo.
 *
 * La demanda se cuenta con {@link ProviderCatalogService#matchesProvider}
 * — la misma fuente que decide si un pedido le llegaría — sobre los leads
 * abiertos ({@code NEW}/{@code PROVIDER_CONTACTED}), sin tráfico
 * {@code [smoke]}. Es el costo real de la demora, no una métrica aparte.
 *
 * Throttle en memoria a propósito (mismo espíritu que el "por corrida" de
 * {@link TelegramNotifyService#notifyAutoReleaseSummary}): no hay entidad
 * donde colgar un evento de proveedor y un reinicio del backend manda como
 * mucho un digest de más. Nunca es spam por lead: es un solo mensaje por
 * corrida notificada.
 */
@Service
public class PendingProviderApprovalScheduler {

  private static final Logger log = LoggerFactory.getLogger(PendingProviderApprovalScheduler.class);
  private static final String SELF_REGISTERED_SOURCE = "autoregistro";
  /** Tope de líneas del digest: si hay una avalancha de registros, ops ve las peores y el total. */
  private static final int MAX_LINES = 10;

  private final ProviderRepository providerRepository;
  private final LeadRepository leadRepository;
  private final ProviderCatalogService providerCatalogService;
  private final TelegramNotifyService telegramNotifyService;
  private final boolean enabled;
  private final long minHours;
  private final long remindEveryHours;
  private final Clock clock;

  /** Última corrida que efectivamente notificó — el throttle del digest. */
  private volatile OffsetDateTime lastNotifiedAt;

  public PendingProviderApprovalScheduler(
      ProviderRepository providerRepository,
      LeadRepository leadRepository,
      ProviderCatalogService providerCatalogService,
      TelegramNotifyService telegramNotifyService,
      @Value("${fixy.providers.pending-approval.enabled:true}") boolean enabled,
      @Value("${fixy.providers.pending-approval.min-hours:12}") long minHours,
      @Value("${fixy.providers.pending-approval.remind-every-hours:24}") long remindEveryHours,
      Clock clock
  ) {
    this.providerRepository = providerRepository;
    this.leadRepository = leadRepository;
    this.providerCatalogService = providerCatalogService;
    this.telegramNotifyService = telegramNotifyService;
    this.enabled = enabled;
    this.minHours = minHours;
    this.remindEveryHours = remindEveryHours;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.providers.pending-approval.scheduler-fixed-delay-ms:3600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int notified = processOnce();
    if (notified > 0) {
      log.info("proveedores esperando aprobación: {} avisados a ops", notified);
    }
  }

  /**
   * Un ciclo del job, invocable desde tests. Devuelve cuántos proveedores
   * pendientes se reportaron (0 si no hay ninguno o si el digest anterior
   * es más nuevo que {@code remind-every-hours}).
   */
  public int processOnce() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    if (lastNotifiedAt != null
        && Duration.between(lastNotifiedAt, now).toHours() < remindEveryHours) {
      return 0;
    }

    List<Provider> pending = providerRepository.findAll().stream()
        .filter(this::isPendingApproval)
        .filter(p -> waitingHours(p, now) >= minHours)
        .toList();
    if (pending.isEmpty()) {
      return 0;
    }

    List<Lead> openLeads = openLeads();
    List<TelegramNotifyService.PendingApproval> rows = new ArrayList<>();
    for (Provider provider : pending) {
      long demand = openLeads.stream()
          .filter(lead -> providerCatalogService.matchesProvider(
              provider, lead.getDetectedCategory(), lead.getLocation()))
          .count();
      rows.add(new TelegramNotifyService.PendingApproval(
          provider, waitingHours(provider, now) / 24, demand));
    }
    // Primero el que más pedidos está dejando sobre la mesa; a igual demanda,
    // el que hace más que espera.
    rows.sort(Comparator
        .comparingLong(TelegramNotifyService.PendingApproval::openLeads).reversed()
        .thenComparing(Comparator.comparingLong(TelegramNotifyService.PendingApproval::daysWaiting).reversed()));

    telegramNotifyService.notifyPendingProviderApprovals(rows, MAX_LINES);
    lastNotifiedAt = now;
    return rows.size();
  }

  private boolean isPendingApproval(Provider provider) {
    if (provider.getId() == null || provider.getStatus() == null) {
      return false;
    }
    if (SmokeTraffic.marks(provider.getName())) {
      return false;
    }
    if (provider.getStatus() == ProviderStatus.NEW) {
      return true;
    }
    // Autoregistro nace INACTIVE (ProviderRegistrationService) y ahí se queda
    // hasta la aprobación: es el caso que nadie ve pasar. Un INACTIVE cargado
    // a mano, en cambio, es una baja decidida.
    return provider.getStatus() == ProviderStatus.INACTIVE
        && SELF_REGISTERED_SOURCE.equalsIgnoreCase(
            provider.getSourceType() == null ? "" : provider.getSourceType().trim().toLowerCase(Locale.ROOT));
  }

  private long waitingHours(Provider provider, OffsetDateTime now) {
    OffsetDateTime since = provider.getCreatedAt();
    if (since == null) {
      return Long.MAX_VALUE;
    }
    return Math.max(0, Duration.between(since, now).toHours());
  }

  /** Pedidos vivos que un proveedor activo podría estar tomando hoy. */
  private List<Lead> openLeads() {
    List<Lead> leads = new ArrayList<>(leadRepository.findByStatusOrderByCreatedAtDesc(LeadStatus.NEW));
    leads.addAll(leadRepository.findByStatusOrderByCreatedAtDesc(LeadStatus.PROVIDER_CONTACTED));
    return leads.stream()
        .filter(lead -> !SmokeTraffic.marks(lead.getProblem()))
        .filter(lead -> lead.getDetectedCategory() != null && !lead.getDetectedCategory().isBlank())
        .toList();
  }
}
