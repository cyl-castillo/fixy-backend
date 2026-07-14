package com.fixy.backend.repository;

import com.fixy.backend.model.UserLead;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserLeadRepository extends JpaRepository<UserLead, Long> {
  Optional<UserLead> findByUserIdAndLeadId(Long userId, Long leadId);
  List<UserLead> findByUserIdOrderByCreatedAtDesc(Long userId);

  /**
   * Salto 1 de memoria de cliente: dado un lead, encuentra el userId del
   * AppUser vinculado (si el cliente se logueó y guardó ese pedido). Vacío
   * si el lead es anónimo — caso mayoritario hoy, tratado explícitamente
   * como "sin memoria" por LeadAgentService.
   */
  @Query("select ul.userId from UserLead ul where ul.leadId = :leadId")
  Optional<Long> findUserIdByLeadId(Long leadId);
}
