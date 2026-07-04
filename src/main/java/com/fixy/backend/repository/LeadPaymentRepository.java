package com.fixy.backend.repository;

import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.LeadPayment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadPaymentRepository extends JpaRepository<LeadPayment, Long> {
  List<LeadPayment> findByCommissionStatusOrderByCreatedAtDesc(CommissionStatus commissionStatus);

  List<LeadPayment> findAllByOrderByCreatedAtDesc();

  Optional<LeadPayment> findByLeadId(Long leadId);

  List<LeadPayment> findByProviderIdOrderByCreatedAtDesc(Long providerId);
}
