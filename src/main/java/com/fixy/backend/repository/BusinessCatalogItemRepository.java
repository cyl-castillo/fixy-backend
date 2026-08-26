package com.fixy.backend.repository;

import com.fixy.backend.model.BusinessCatalogItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessCatalogItemRepository extends JpaRepository<BusinessCatalogItem, Long> {

  /** Lista completa (incluye inactivos, con el flag {@code active} — ver
   * GET /api/businesses/{id}/catalog) para que ops vea qué se dio de baja. */
  List<BusinessCatalogItem> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

  Optional<BusinessCatalogItem> findByIdAndBusinessId(Long id, Long businessId);

  /** Universo del motor de respuesta (Fase 2, {@code CatalogAnswerService}): solo ítems vigentes. */
  List<BusinessCatalogItem> findByBusinessIdAndActiveTrue(Long businessId);
}
