package com.fixy.backend.repository;

import com.fixy.backend.model.BusinessHour;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessHourRepository extends JpaRepository<BusinessHour, Long> {

  List<BusinessHour> findByBusinessIdOrderByDayOfWeekAscOpensAtAsc(Long businessId);

  /** Usado por el PUT que reemplaza el set completo (ver
   * BusinessHourService.replace): borra todas las franjas actuales antes de
   * insertar las nuevas. */
  void deleteByBusinessId(Long businessId);
}
