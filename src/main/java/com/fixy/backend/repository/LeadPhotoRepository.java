package com.fixy.backend.repository;

import com.fixy.backend.model.LeadPhoto;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadPhotoRepository extends JpaRepository<LeadPhoto, Long> {
  List<LeadPhoto> findByLeadIdOrderByCreatedAtAsc(Long leadId);
  int countByLeadId(Long leadId);

  /** Refundación de Fixy, fase 2 (contrato §B.4): fotos subidas POR el
   * proveedor (no por el cliente/ops) — la evidencia obligatoria para
   * completar un trabajo remoto. */
  int countByLeadIdAndProviderIdIsNotNull(Long leadId);
}
