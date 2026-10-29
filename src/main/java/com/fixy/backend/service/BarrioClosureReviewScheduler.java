package com.fixy.backend.service;

import com.fixy.backend.model.BarrioClosureClaim;
import com.fixy.backend.repository.BarrioClosureClaimRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hermano de {@link BarrioAiReviewScheduler}: cada 60 s hasta 5 cierres PENDING_REVIEW sin revisión válida. */
@Component
public class BarrioClosureReviewScheduler {

  private static final Logger log = LoggerFactory.getLogger(BarrioClosureReviewScheduler.class);
  static final int BATCH = 5;

  private final BarrioClosureClaimRepository closures;
  private final BarrioClosureReviewService review;
  private final boolean enabled;

  public BarrioClosureReviewScheduler(
      BarrioClosureClaimRepository closures,
      BarrioClosureReviewService review,
      @Value("${fixy.barrio.ai-review.enabled:true}") boolean enabled
  ) {
    this.closures = closures;
    this.review = review;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${fixy.barrio.ai-review.scheduler-fixed-delay-ms:60000}",
      initialDelayString = "${fixy.barrio.ai-review.scheduler-initial-delay-ms:30000}")
  public void tick() {
    runOnce();
  }

  /** Una pasada; devuelve cuántos cierres procesó. Público para tests. */
  public int runOnce() {
    if (!enabled || !review.isActive()) {
      return 0;
    }
    List<BarrioClosureClaim> batch = closures.findAiCandidates(BarrioClosureReviewService.MAX_ATTEMPTS,
        PageRequest.of(0, BATCH));
    for (BarrioClosureClaim c : batch) {
      try {
        review.review(c);
      } catch (RuntimeException e) {
        log.warn("barrio IA cierre #{}: pasada falló: {}", c.getId(), e.getMessage());
      }
    }
    return batch.size();
  }
}
