package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * "La promesa que nadie cumplía" (hallazgo 2026-07-29): cuando un pedido
 * quedaba listo para matching y en ese momento NO había proveedor para su
 * categoría/zona, el agente le decía al cliente "te aviso por acá apenas
 * alguien levante el pedido"... y nada volvía a intentarlo jamás.
 * {@link LeadAgentService#tryAutoMatch} corre una sola vez, en el instante
 * en que el lead cruza a readyForMatching, así que un proveedor que se da de
 * alta DESPUÉS nunca ve la demanda que ya estaba esperando por él.
 *
 * Dato del embudo de prod que lo motivó: 5 pedidos reales esperando con
 * proveedor ACTIVO ya registrado en su misma categoría y zona — 3 de aires
 * (#128/#135/#147, Carnot Clima se registró el 23/07) y 2 de decoración
 * (#150/#180, Daya Dream Deco el 25/07). Todos con UN solo evento en la
 * timeline: el de creación. Y es el patrón que se repite cada vez que Carlos
 * capta un proveedor nuevo para una categoría con demanda acumulada
 * (jardinería y plomería son las que están en captación hoy).
 *
 * Alcance deliberadamente angosto: SOLO leads que nunca entraron a matching
 * (sin evento MATCH_GENERATED ni PROVIDER_CONTACTED). Los que sí entraron y
 * nadie tomó ya los cubre {@link MatchingStaleScheduler} — este job no pisa
 * ese tramo ni duplica sus avisos.
 */
@Service
public class OrphanMatchRetryScheduler {

  private static final Logger log = LoggerFactory.getLogger(OrphanMatchRetryScheduler.class);

  /** Estados donde un pedido todavía espera proveedor propio. */
  private static final Set<LeadStatus> WAITING_STATUSES = Set.of(LeadStatus.NEW, LeadStatus.IN_REVIEW);
  /** Si existe alguno de estos, el matching YA arrancó: no es un huérfano. */
  private static final Set<String> MATCHING_STARTED_EVENT_TYPES = Set.of("MATCH_GENERATED", "PROVIDER_CONTACTED");
  /** Tope por ciclo: si un alta nueva destraba mucha demanda vieja, se ofrece de a poco. */
  private static final int MAX_PER_RUN = 10;

  private final LeadRepository leadRepository;
  private final LeadEventRepository leadEventRepository;
  private final LeadAgentService leadAgentService;
  private final boolean enabled;
  private final long maxAgeDays;
  private final Clock clock;

  public OrphanMatchRetryScheduler(
      LeadRepository leadRepository,
      LeadEventRepository leadEventRepository,
      LeadAgentService leadAgentService,
      @Value("${fixy.orphan-match-retry.enabled:true}") boolean enabled,
      @Value("${fixy.orphan-match-retry.max-age-days:14}") long maxAgeDays,
      Clock clock
  ) {
    this.leadRepository = leadRepository;
    this.leadEventRepository = leadEventRepository;
    this.leadAgentService = leadAgentService;
    this.enabled = enabled;
    this.maxAgeDays = maxAgeDays;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.orphan-match-retry.scheduler-fixed-delay-ms:900000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int matched = processOnce();
    if (matched > 0) {
      log.info("reintento de matching: {} pedido(s) huérfanos consiguieron proveedor", matched);
    }
  }

  /** Un ciclo del job, invocable desde tests. Devuelve cuántos leads consiguieron proveedor. */
  public int processOnce() {
    // Ventana de 14 días: la misma que usa el brazo de captación para definir
    // "demanda viva". Más viejo que eso, ofrecerlo es ruido para el proveedor
    // (el cliente ya resolvió por otro lado) y una falsa expectativa.
    OffsetDateTime oldestAllowed = OffsetDateTime.now(clock).minusDays(maxAgeDays);

    List<Lead> candidates = new ArrayList<>();
    for (LeadStatus status : WAITING_STATUSES) {
      candidates.addAll(leadRepository.findByStatusOrderByCreatedAtDesc(status));
    }

    int matched = 0;
    int skippedByCap = 0;
    for (Lead lead : candidates) {
      if (!isOrphanWaiting(lead, oldestAllowed)) {
        continue;
      }
      if (matched >= MAX_PER_RUN) {
        skippedByCap++;
        continue;
      }
      if (leadAgentService.retryAutoMatch(lead.getId())) {
        matched++;
      }
    }
    if (skippedByCap > 0) {
      log.info("reintento de matching: {} pedido(s) elegibles quedaron para el próximo ciclo (tope {})",
          skippedByCap, MAX_PER_RUN);
    }
    return matched;
  }

  private boolean isOrphanWaiting(Lead lead, OffsetDateTime oldestAllowed) {
    if (lead.getId() == null || lead.isDisputed()) {
      return false;
    }
    // Mismo guard anti-tráfico-sintético que el resto de los schedulers.
    String problem = lead.getProblem();
    if (problem != null && problem.toLowerCase(Locale.ROOT).contains("[smoke]")) {
      return false;
    }
    // Sin categoría+zona resueltas todavía es intake: territorio del
    // ReengagementScheduler, no de este job.
    if (!lead.isReadyForMatching()) {
      return false;
    }
    // Ya tiene proveedor contactado: nada que reintentar.
    if (lead.getAssignedProviderId() != null) {
      return false;
    }
    if (lead.getCreatedAt() == null || lead.getCreatedAt().isBefore(oldestAllowed)) {
      return false;
    }
    return !hasMatchingStarted(lead.getId());
  }

  private boolean hasMatchingStarted(Long leadId) {
    return MATCHING_STARTED_EVENT_TYPES.stream()
        .anyMatch(type -> !leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, type).isEmpty());
  }
}
