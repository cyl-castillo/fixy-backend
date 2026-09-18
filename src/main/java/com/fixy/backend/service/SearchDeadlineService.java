package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Hora límite de búsqueda de técnico (Tier 2, contrato §B.3): fuente única
 * para arrancar/reiniciar {@code Lead.searchDeadlineAt} y para formatear la
 * hora local que ven el vecino y ops. Servicio chico y sin otras
 * dependencias a propósito — lo usan {@link LeadAgentService}, {@link
 * OrderService}, {@link ProviderSelfService} y
 * {@link com.fixy.backend.controller.PublicLeadController} (cambio de
 * franja) sin introducir un ciclo de beans entre ellos.
 */
@Service
public class SearchDeadlineService {

  private static final ZoneId MONTEVIDEO = ZoneId.of("America/Montevideo");
  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

  private final Clock clock;
  private final long deadlineMinutes;

  public SearchDeadlineService(
      Clock clock,
      @Value("${fixy.matching.search-deadline-minutes:120}") long deadlineMinutes
  ) {
    this.clock = clock;
    this.deadlineMinutes = deadlineMinutes;
  }

  /**
   * Arranca (o reinicia) la hora límite: {@code now + deadlineMinutes}. Se
   * llama al quedar {@code readyForMatching=true} (pedido estructurado,
   * intake conversacional, plan Casa a distancia — mismo único punto de
   * creación de pedidos, {@code OrderService.createInternal}) y se reinicia
   * al cambiar la franja o al volver al pozo (AUTO_RELEASED/PROVIDER_RELEASED).
   */
  public void begin(Lead lead) {
    lead.setSearchDeadlineAt(OffsetDateTime.now(clock).plusMinutes(deadlineMinutes));
  }

  /** {@code HH:mm} en hora local de Montevideo — null-safe (cadena vacía si
   * el lead no tiene deadline, para poder usarlo directo en un formatted()). */
  public String formatHHmm(OffsetDateTime deadline) {
    if (deadline == null) {
      return "";
    }
    return deadline.atZoneSameInstant(MONTEVIDEO).format(HH_MM);
  }

  public OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }
}
