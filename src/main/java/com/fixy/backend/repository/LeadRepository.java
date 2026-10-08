package com.fixy.backend.repository;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LeadRepository extends JpaRepository<Lead, Long> {
  List<Lead> findAllByOrderByCreatedAtDesc();
  List<Lead> findByStatusOrderByCreatedAtDesc(LeadStatus status);
  List<Lead> findByAssignedProviderIgnoreCaseOrderByCreatedAtDesc(String assignedProvider);
  List<Lead> findByAssignedProviderIdOrderByCreatedAtDesc(Long assignedProviderId);
  List<Lead> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(OffsetDateTime from, OffsetDateTime to);

  /** OfferResponse.leadCount (CTA "Pedir por Fixy", FIXY_OFERTAS_CTA_DESIGN.md §3.2). */
  long countBySourceOfferId(Long sourceOfferId);

  /**
   * Leads de un canal+telefono dado, mas nuevos primero. Usado por el intake
   * de WhatsApp para decidir si un mensaje entrante continua una conversacion
   * abierta (mismo phone+channel, status no terminal) o inicia una nueva.
   */
  List<Lead> findByPhoneAndChannelOrderByCreatedAtDesc(String phone, String channel);

  /** Refundación fase 2 (contrato §B.3): pedidos originados en un plan Casa
   * a distancia, para la página del dueño. */
  List<Lead> findByRemoteCarePlanIdOrderByCreatedAtDesc(Long remoteCarePlanId);

  /** H2.4: candidatos a auto-confirmación — completados, sin disputa y sin
   * haber corrido ya el scheduler. El filtro de "sin rating" y de las 72h
   * desde el evento de completado se aplica en el servicio (requiere leer
   * LeadEvent, no es expresable acá sin un join manual). */
  List<Lead> findByStatusAndDisputedFalseAndClosingAutoConfirmedAtIsNull(LeadStatus status);

  /** Tier 2 (contrato §B.3): candidatos al frente "deadline" del watchdog —
   * el filtro fino (deadline vencido, sin evento SEARCH_DEADLINE_MISSED
   * posterior, sin smoke) se aplica en el servicio, igual que el resto de
   * MatchingWatchdogScheduler. */
  /** Tier 2 (contrato §B.3): candidatos a "hora límite vencida" — por ESTADO,
   * no por assignedProviderId: un lead PROVIDER_CONTACTED tiene el id del
   * técnico contactado aunque nadie haya aceptado (es justo el caso del
   * técnico que no contesta). */
  List<Lead> findBySearchDeadlineAtIsNotNullAndDisputedFalseAndStatusIn(java.util.Collection<LeadStatus> statuses);

  /**
   * Transición atómica al aceptar una oportunidad: solo asigna si el lead
   * SIGUE sin proveedor asignado (o reservado para ESTE proveedor) y en un
   * status matcheable. Devuelve cuántas filas tocó (1 = esta invocación ganó
   * la asignación, 0 = otro proveedor ya lo tomó, o cambió de status entre el
   * GET y el accept). Mismo patrón que
   * {@code LeadPaymentRepository.markPaidIfNotAlready}: el primero que
   * escribe gana, sin locks explícitos ni race en memoria.
   *
   * <p>Bug del 2026-10-08 (primer SÍ real por WhatsApp, lead #275): el
   * matching deja el lead PROVIDER_CONTACTED con {@code assignedProviderId}
   * = el técnico contactado (ver {@code LeadMatchingService.contactTopMatch}
   * y el javadoc de {@code findBySearchDeadlineAt...}), así que la condición
   * "assignedProviderId is null" rechazaba con 409 justamente al técnico al
   * que se le ofreció el trabajo. El panel no lo sufría porque acepta por
   * {@code /leads/{id}/status}, no por este camino. Ahora el reservado puede
   * aceptar; otro técnico sigue sin poder robarlo.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query("update Lead l set l.status = com.fixy.backend.model.LeadStatus.ASSIGNED, "
      + "l.assignedProviderId = :providerId, l.assignedProvider = :providerName "
      + "where l.id = :leadId and (l.assignedProviderId is null or l.assignedProviderId = :providerId) "
      + "and l.status in :matchableStatuses")
  int assignIfUnclaimed(
      @Param("leadId") Long leadId,
      @Param("providerId") Long providerId,
      @Param("providerName") String providerName,
      @Param("matchableStatuses") List<LeadStatus> matchableStatuses
  );
}
