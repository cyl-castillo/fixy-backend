package com.fixy.backend.service;

import com.fixy.backend.model.BarrioReport;
import com.fixy.backend.repository.BarrioReportRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cada 60 s toma hasta 5 reclamos PENDING_REVIEW sin revisión válida y se los pasa a la IA. */
@Component
public class BarrioAiReviewScheduler {

  private static final Logger log = LoggerFactory.getLogger(BarrioAiReviewScheduler.class);
  static final int BATCH = 5;

  private final BarrioReportRepository reports;
  private final BarrioAiReviewService review;
  private final boolean enabled;

  public BarrioAiReviewScheduler(
      BarrioReportRepository reports,
      BarrioAiReviewService review,
      @Value("${fixy.barrio.ai-review.enabled:true}") boolean enabled
  ) {
    this.reports = reports;
    this.review = review;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${fixy.barrio.ai-review.scheduler-fixed-delay-ms:60000}",
      initialDelayString = "${fixy.barrio.ai-review.scheduler-initial-delay-ms:30000}")
  public void tick() {
    runOnce();
  }

  /** Una pasada; devuelve cuántos reclamos procesó. Público para tests. */
  public int runOnce() {
    if (!enabled || !review.isActive()) {
      return 0;
    }
    List<BarrioReport> batch = reports.findAiCandidates(BarrioAiReviewService.MAX_ATTEMPTS, PageRequest.of(0, BATCH));
    for (BarrioReport r : batch) {
      try {
        review.review(r);
      } catch (RuntimeException e) {
        log.warn("barrio IA #{}: pasada falló: {}", r.getId(), e.getMessage());
      }
    }
    return batch.size();
  }
}
