package com.fixy.backend.repository;

import com.fixy.backend.model.CustomerPayment;
import com.fixy.backend.model.CustomerPaymentStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CustomerPaymentRepository extends JpaRepository<CustomerPayment, Long> {

  Optional<CustomerPayment> findByLeadId(Long leadId);

  List<CustomerPayment> findByRemoteCarePlanIdOrderByCreatedAtDesc(Long remoteCarePlanId);

  List<CustomerPayment> findByStatusOrderByCreatedAtDesc(CustomerPaymentStatus status);

  List<CustomerPayment> findAllByOrderByCreatedAtDesc();

  List<CustomerPayment> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(OffsetDateTime from, OffsetDateTime to);

  List<CustomerPayment> findByPaidAtGreaterThanEqualAndPaidAtLessThan(OffsetDateTime from, OffsetDateTime to);

  /**
   * Recordatorio único (contrato §A.4.5): SERVICE_FEE PENDING con link,
   * created_at > 48h, remindedAt null.
   */
  @Query("select p from CustomerPayment p where p.status = com.fixy.backend.model.CustomerPaymentStatus.PENDING "
      + "and p.kind = com.fixy.backend.model.CustomerPaymentKind.SERVICE_FEE "
      + "and p.mpPaymentLink is not null and p.remindedAt is null "
      + "and p.createdAt < :cutoff")
  List<CustomerPayment> findServiceFeeDueForReminder(@Param("cutoff") OffsetDateTime cutoff);

  /**
   * Transición atómica a PAID: mismo patrón idempotente que {@code
   * LeadPaymentRepository#markPaidIfNotAlready} — MP puede notificar el
   * mismo pago dos veces casi simultáneas. Devuelve cuántas filas tocó (1 =
   * esta invocación ganó la transición, 0 = ya estaba pagado).
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query("update CustomerPayment p set p.status = com.fixy.backend.model.CustomerPaymentStatus.PAID, "
      + "p.paidAt = :paidAt, p.mpPaymentId = :mpPaymentId, p.guaranteeUntil = :guaranteeUntil "
      + "where p.id = :id and p.status <> com.fixy.backend.model.CustomerPaymentStatus.PAID")
  int markPaidIfNotAlready(
      @Param("id") Long id,
      @Param("mpPaymentId") String mpPaymentId,
      @Param("paidAt") OffsetDateTime paidAt,
      @Param("guaranteeUntil") OffsetDateTime guaranteeUntil
  );
}
