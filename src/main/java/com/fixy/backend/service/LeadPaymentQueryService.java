package com.fixy.backend.service;

import com.fixy.backend.dto.LeadPaymentSummary;
import com.fixy.backend.dto.ProviderCommissionSummary;
import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.LeadRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Consultas de solo lectura de comisiones para el panel ops (H1.5) y para
 * el panel self-service del proveedor (H_B).
 */
@Service
public class LeadPaymentQueryService {

  /** Prefijo de mpPaymentId cuando el cobro se marcó a mano desde el panel
   * admin (transferencia bancaria u otro medio fuera de Mercado Pago) — así
   * queda distinguible en el dato de un pago real confirmado por MP. Caso
   * real: Nueva Era pagando los $250 de comisión por transferencia. */
  private static final String MANUAL_PAYMENT_PREFIX = "MANUAL:";

  /** Prefijo de mpPaymentId cuando la comisión se condona desde el panel
   * admin (Fixy decide no cobrarla — cortesía, error, ajuste). Caso real:
   * comisión #1 ($10, smoke lead #103) quedó PENDING desde el 14 sin forma
   * de cerrarla salvo SQL a mano. */
  private static final String WAIVED_PREFIX = "WAIVED:";

  private static final java.util.Set<CommissionStatus> WAIVABLE_STATUSES =
      java.util.Set.of(CommissionStatus.PENDING, CommissionStatus.OVERDUE);

  private final LeadPaymentRepository leadPaymentRepository;
  private final LeadRepository leadRepository;
  private final LeadTimelineService leadTimelineService;
  private final CommissionService commissionService;
  private final Clock clock;

  public LeadPaymentQueryService(
      LeadPaymentRepository leadPaymentRepository,
      LeadRepository leadRepository,
      LeadTimelineService leadTimelineService,
      CommissionService commissionService,
      Clock clock
  ) {
    this.leadPaymentRepository = leadPaymentRepository;
    this.leadRepository = leadRepository;
    this.leadTimelineService = leadTimelineService;
    this.commissionService = commissionService;
    this.clock = clock;
  }

  public List<LeadPaymentSummary> list(CommissionStatus statusFilter) {
    var payments = statusFilter != null
        ? leadPaymentRepository.findByCommissionStatusOrderByCreatedAtDesc(statusFilter)
        : leadPaymentRepository.findAllByOrderByCreatedAtDesc();
    return payments.stream().map(LeadPaymentSummary::fromEntity).toList();
  }

  /**
   * Marca una comisión como cobrada manualmente (transferencia u otro medio
   * fuera de Mercado Pago) desde el panel admin. Reusa la misma transición
   * atómica {@code markPaidIfNotAlready} que el webhook de MP para
   * garantizar idempotencia: si ya estaba PAID, esta llamada es un no-op y
   * devuelve el estado actual en vez de fallar o duplicar el evento.
   */
  public LeadPaymentSummary markPaidManually(Long id, String note) {
    LeadPayment payment = leadPaymentRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead payment not found"));

    // Reactivación instantánea (FIXY_COBRANZAS.md): leído ANTES de la
    // transición atómica a propósito.
    boolean wasOverdue = payment.getCommissionStatus() == CommissionStatus.OVERDUE;

    String marker = MANUAL_PAYMENT_PREFIX + (note == null || note.isBlank() ? "transferencia" : note.trim());
    int transitioned = leadPaymentRepository.markPaidIfNotAlready(
        id, marker, OffsetDateTime.now(clock), CommissionStatus.PAID);

    if (transitioned == 0) {
      // Ya estaba PAID (por MP o por un click anterior de "marcar cobrada")
      // — idempotente, devolvemos el estado actual sin duplicar el evento.
      return LeadPaymentSummary.fromEntity(leadPaymentRepository.findById(id).orElseThrow());
    }

    Optional<Lead> lead = leadRepository.findById(payment.getLeadId());
    lead.ifPresent(value -> leadTimelineService.appendEvent(value, "COMMISSION_PAID", "ops",
        "Comisión %s %s marcada cobrada manualmente (%s)".formatted(
            payment.getCurrency(), payment.getCommissionAmount(), marker.substring(MANUAL_PAYMENT_PREFIX.length()))));

    if (wasOverdue) {
      commissionService.notifySettled(payment, lead.orElse(null));
    }

    return LeadPaymentSummary.fromEntity(leadPaymentRepository.findById(id).orElseThrow());
  }

  /**
   * Condona una comisión (Fixy decide no cobrarla) desde el panel admin.
   * Solo permitido desde PENDING/OVERDUE — no se condona una comisión ya
   * PAID (409). Idempotente: repetir la llamada sobre una comisión ya
   * WAIVED es un no-op que devuelve el estado actual, mismo patrón que
   * {@link #markPaidManually}. Si la comisión condonada estaba OVERDUE, la
   * pausa de matching se levanta sola (el filtro de
   * {@code ProviderCatalogService.findMatches} solo excluye proveedores con
   * comisiones OVERDUE actuales) — se notifica igual que un pago saldado.
   */
  public LeadPaymentSummary waiveManually(Long id, String note) {
    LeadPayment payment = leadPaymentRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead payment not found"));

    if (payment.getCommissionStatus() == CommissionStatus.PAID) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "no se puede condonar una comisión ya cobrada");
    }
    if (payment.getCommissionStatus() == CommissionStatus.WAIVED) {
      // Idempotente: ya estaba condonada, no-op.
      return LeadPaymentSummary.fromEntity(payment);
    }

    boolean wasOverdue = payment.getCommissionStatus() == CommissionStatus.OVERDUE;

    String marker = WAIVED_PREFIX + (note == null || note.isBlank() ? "condonada" : note.trim());
    int transitioned = leadPaymentRepository.waiveIfWaivable(
        id, marker, CommissionStatus.WAIVED, WAIVABLE_STATUSES);

    if (transitioned == 0) {
      // Carrera perdida (otro request cambió el estado entre el findById de
      // arriba y este update): releemos y tratamos igual que al principio.
      LeadPayment current = leadPaymentRepository.findById(id).orElseThrow();
      if (current.getCommissionStatus() == CommissionStatus.PAID) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "no se puede condonar una comisión ya cobrada");
      }
      return LeadPaymentSummary.fromEntity(current);
    }

    Optional<Lead> lead = leadRepository.findById(payment.getLeadId());
    lead.ifPresent(value -> leadTimelineService.appendEvent(value, "COMMISSION_WAIVED", "ops",
        "Comisión %s %s condonada (%s)".formatted(
            payment.getCurrency(), payment.getCommissionAmount(), marker.substring(WAIVED_PREFIX.length()))));

    if (wasOverdue) {
      commissionService.notifySettled(payment, lead.orElse(null));
    }

    return LeadPaymentSummary.fromEntity(leadPaymentRepository.findById(id).orElseThrow());
  }

  /**
   * Totales de comisión (pendiente vs pagada) de un proveedor puntual, para
   * que el panel self-service muestre "cuánto le debe a Fixy" sin exponer
   * el detalle fila por fila (eso es de ops). Si el proveedor no tiene
   * ningún LeadPayment (payments deshabilitado, o sin leads COMPLETED
   * todavía), devuelve {@link ProviderCommissionSummary#empty()} — nunca
   * null ni un error, el front distingue "sin comisiones" de una falla.
   */
  public ProviderCommissionSummary summaryFor(Long providerId) {
    List<LeadPayment> payments = leadPaymentRepository.findByProviderIdOrderByCreatedAtDesc(providerId);
    if (payments.isEmpty()) {
      return ProviderCommissionSummary.empty();
    }

    BigDecimal pending = BigDecimal.ZERO;
    int pendingCount = 0;
    BigDecimal paid = BigDecimal.ZERO;
    int paidCount = 0;
    String currency = payments.get(0).getCurrency();

    BigDecimal earningsThisMonth = BigDecimal.ZERO;
    int earningsThisMonthCount = 0;
    BigDecimal earningsTotal = BigDecimal.ZERO;
    int earningsTotalCount = 0;
    OffsetDateTime now = OffsetDateTime.now(clock);
    List<ProviderCommissionSummary.PendingCommission> pendingItems = new java.util.ArrayList<>();

    for (LeadPayment payment : payments) {
      if (payment.getCommissionStatus() == CommissionStatus.PAID) {
        paid = paid.add(payment.getCommissionAmount());
        paidCount++;
      } else if (payment.getCommissionStatus() == CommissionStatus.PENDING
          || payment.getCommissionStatus() == CommissionStatus.OVERDUE) {
        pending = pending.add(payment.getCommissionAmount());
        pendingCount++;
        // Item con link de pago para el botón "Pagar" del panel (ver javadoc
        // de PendingCommission). mpPaymentLink puede ser null si MP estaba
        // apagado al crearla — el front muestra el item igual, sin botón.
        pendingItems.add(new ProviderCommissionSummary.PendingCommission(
            payment.getLeadId(),
            payment.getCommissionAmount(),
            payment.getCurrency(),
            payment.getMpPaymentLink(),
            payment.getCreatedAt(),
            payment.getCommissionStatus() == CommissionStatus.OVERDUE));
      }
      // WAIVED no suma a ninguno de los dos totales: Fixy decidió no cobrarla.

      // Ingresos propios (lo que el proveedor GANÓ, no lo que le debe a
      // Fixy): todo LeadPayment representa un lead COMPLETED con monto
      // cobrado real, sin importar el estado de la comisión.
      earningsTotal = earningsTotal.add(payment.getAmountCharged());
      earningsTotalCount++;
      if (isSameMonth(payment.getCreatedAt(), now)) {
        earningsThisMonth = earningsThisMonth.add(payment.getAmountCharged());
        earningsThisMonthCount++;
      }
    }

    return new ProviderCommissionSummary(
        pending, pendingCount, paid, paidCount, currency,
        earningsThisMonth, earningsThisMonthCount, earningsTotal, earningsTotalCount,
        List.copyOf(pendingItems));
  }

  private boolean isSameMonth(OffsetDateTime a, OffsetDateTime b) {
    return a.getYear() == b.getYear() && a.getMonth() == b.getMonth();
  }
}
