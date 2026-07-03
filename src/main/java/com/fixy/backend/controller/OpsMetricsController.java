package com.fixy.backend.controller;

import com.fixy.backend.dto.OpsDailyMetricsResponse;
import com.fixy.backend.service.OpsMetricsService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ops/metrics")
public class OpsMetricsController {

  private static final int DEFAULT_WINDOW_DAYS = 7;

  private final OpsMetricsService opsMetricsService;

  public OpsMetricsController(OpsMetricsService opsMetricsService) {
    this.opsMetricsService = opsMetricsService;
  }

  /**
   * from/to aceptan tanto fecha simple ISO ("2026-06-25") como date-time ISO
   * con offset ("2026-06-25T00:00:00-03:00"). Si no se pasan, default a los
   * últimos 7 días (hoy incluido, ventana [now - 7d, now)).
   */
  @GetMapping("/daily")
  public OpsDailyMetricsResponse daily(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to
  ) {
    OffsetDateTime now = OffsetDateTime.now();
    OffsetDateTime toDate = to != null ? parseDateTime(to, "to") : now;
    OffsetDateTime fromDate = from != null ? parseDateTime(from, "from") : toDate.minusDays(DEFAULT_WINDOW_DAYS);

    if (fromDate.isAfter(toDate)) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "'from' debe ser anterior o igual a 'to'"
      );
    }

    return opsMetricsService.dailyMetrics(fromDate, toDate);
  }

  private OffsetDateTime parseDateTime(String value, String paramName) {
    try {
      return OffsetDateTime.parse(value);
    } catch (DateTimeParseException ignored) {
      // no era date-time completo; probamos como fecha simple (asumimos
      // inicio de día en UTC para mantenerlo simple y predecible).
    }

    try {
      return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    } catch (DateTimeParseException exception) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "'%s' inválido: usar fecha ISO (yyyy-MM-dd) o date-time ISO con offset".formatted(paramName)
      );
    }
  }
}
