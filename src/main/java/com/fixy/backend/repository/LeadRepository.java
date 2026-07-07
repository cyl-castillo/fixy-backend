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

  /** H2.4: candidatos a auto-confirmación — completados, sin disputa y sin
   * haber corrido ya el scheduler. El filtro de "sin rating" y de las 72h
   * desde el evento de completado se aplica en el servicio (requiere leer
   * LeadEvent, no es expresable acá sin un join manual). */
  List<Lead> findByStatusAndDisputedFalseAndClosingAutoConfirmedAtIsNull(LeadStatus status);

  /**
   * Transición atómica al aceptar una oportunidad: solo asigna si el lead
   * SIGUE sin proveedor asignado y en un status matcheable. Devuelve cuántas
   * filas tocó (1 = esta invocación ganó la asignación, 0 = otro proveedor
   * ya lo tomó, o cambió de status entre el GET y el accept). Mismo patrón
   * que {@code LeadPaymentRepository.markPaidIfNotAlready}: el primero que
   * escribe gana, sin locks explícitos ni race en memoria.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query("update Lead l set l.status = com.fixy.backend.model.LeadStatus.ASSIGNED, "
      + "l.assignedProviderId = :providerId, l.assignedProvider = :providerName "
      + "where l.id = :leadId and l.assignedProviderId is null and l.status in :matchableStatuses")
  int assignIfUnclaimed(
      @Param("leadId") Long leadId,
      @Param("providerId") Long providerId,
      @Param("providerName") String providerName,
      @Param("matchableStatuses") List<LeadStatus> matchableStatuses
  );
}
