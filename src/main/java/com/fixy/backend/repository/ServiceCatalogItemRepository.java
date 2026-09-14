package com.fixy.backend.repository;

import com.fixy.backend.model.ServiceCatalogItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceCatalogItemRepository extends JpaRepository<ServiceCatalogItem, Long> {

  List<ServiceCatalogItem> findAllByOrderBySortOrderAscIdAsc();

  List<ServiceCatalogItem> findByActiveTrueOrderBySortOrderAscIdAsc();

  List<ServiceCatalogItem> findByCategoryAndActiveTrueOrderBySortOrderAscIdAsc(String category);

  Optional<ServiceCatalogItem> findByCode(String code);

  Optional<ServiceCatalogItem> findByCodeAndActiveTrue(String code);

  boolean existsByCode(String code);
}
