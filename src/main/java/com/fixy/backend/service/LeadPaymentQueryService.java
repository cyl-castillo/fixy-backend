package com.fixy.backend.service;

import com.fixy.backend.dto.LeadPaymentSummary;
import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.repository.LeadPaymentRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Consultas de solo lectura de comisiones para el panel ops (H1.5).
 */
@Service
public class LeadPaymentQueryService {

  private final LeadPaymentRepository leadPaymentRepository;

  public LeadPaymentQueryService(LeadPaymentRepository leadPaymentRepository) {
    this.leadPaymentRepository = leadPaymentRepository;
  }

  public List<LeadPaymentSummary> list(CommissionStatus statusFilter) {
    var payments = statusFilter != null
        ? leadPaymentRepository.findByCommissionStatusOrderByCreatedAtDesc(statusFilter)
        : leadPaymentRepository.findAllByOrderByCreatedAtDesc();
    return payments.stream().map(LeadPaymentSummary::fromEntity).toList();
  }
}
