package com.fixy.backend.core.cases;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Core Fase 3: persistencia de {@link AgentTurn}. */
public interface AgentTurnRepository extends JpaRepository<AgentTurn, Long> {

  /** Cronológico (el id desempata filas del mismo instante). */
  List<AgentTurn> findByLeadIdOrderByCreatedAtAscIdAsc(Long leadId);
}
