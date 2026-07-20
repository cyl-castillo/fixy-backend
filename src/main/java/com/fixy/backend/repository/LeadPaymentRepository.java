package com.fixy.backend.repository;

import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.LeadPayment;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LeadPaymentRepository extends JpaRepository<LeadPayment, Long> {
  List<LeadPayment> findByCommissionStatusOrderByCreatedAtDesc(CommissionStatus commissionStatus);

  List<LeadPayment> findAllByOrderByCreatedAtDesc();

  Optional<LeadPayment> findByLeadId(Long leadId);

  List<LeadPayment> findByProviderIdOrderByCreatedAtDesc(Long providerId);

  /** Cuántos LeadPayment tiene el proveedor en total (incluye todos los
   * estados) — usado por {@code CommissionOverdueScheduler} para detectar
   * "es su primera comisión" (tolera el doble de plazo, está aprendiendo
   * el sistema) sin cargar la lista completa. */
  long countByProviderId(Long providerId);

  /** Set de providerIds con al menos una comisión OVERDUE, para que el
   * filtro de matching ({@code ProviderCatalogService.findMatches}) y la
   * bandeja de oportunidades ({@code ProviderOpportunityService.listFor})
   * puedan excluir en O(1) por proveedor en vez de un query por proveedor
   * dentro del stream. */
  @Query("select distinct p.providerId from LeadPayment p where p.commissionStatus = :status")
  Set<Long> findProviderIdsByCommissionStatus(@Param("status") CommissionStatus status);

  /**
   * Transición atómica a PAID: solo escribe si el registro NO estaba ya PAID
   * y devuelve cuántas filas tocó (1 = esta invocación ganó la transición,
   * 0 = ya estaba pagado). Mercado Pago puede mandar la misma notificación
   * dos veces casi simultáneas (visto en sandbox: 17 ms de diferencia) y un
   * check-then-act en memoria deja pasar a ambas.
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query("update LeadPayment p set p.commissionStatus = :paid, p.paidAt = :paidAt, p.mpPaymentId = :mpPaymentId "
      + "where p.id = :id and p.commissionStatus <> :paid")
  int markPaidIfNotAlready(
      @Param("id") Long id,
      @Param("mpPaymentId") String mpPaymentId,
      @Param("paidAt") OffsetDateTime paidAt,
      @Param("paid") CommissionStatus paid
  );

  /**
   * Transición atómica a WAIVED: solo escribe si el registro está en un
   * estado condonable (PENDING u OVERDUE — no se condona algo ya PAID).
   * Devuelve cuántas filas tocó (1 = esta invocación ganó la transición,
   * 0 = no estaba en un estado condonable). El llamador decide, mirando el
   * estado actual, si el 0 significa "ya estaba WAIVED" (idempotente) o
   * "está PAID" (409, no se condona lo cobrado).
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query("update LeadPayment p set p.commissionStatus = :waived, p.mpPaymentId = :mpPaymentId "
      + "where p.id = :id and p.commissionStatus in :waivable")
  int waiveIfWaivable(
      @Param("id") Long id,
      @Param("mpPaymentId") String mpPaymentId,
      @Param("waived") CommissionStatus waived,
      @Param("waivable") java.util.Collection<CommissionStatus> waivable
  );
}
