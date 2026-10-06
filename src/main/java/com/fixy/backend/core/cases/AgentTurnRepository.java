package com.fixy.backend.core.cases;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Core Fase 3: persistencia de {@link AgentTurn}. */
public interface AgentTurnRepository extends JpaRepository<AgentTurn, Long> {

  /** Cronológico (el id desempata filas del mismo instante). */
  List<AgentTurn> findByLeadIdOrderByCreatedAtAscIdAsc(Long leadId);

  /**
   * Cuántas filas de ese {@code kind} hay por {@code outcome} en la ventana [from, to). Agregado en
   * la base: las filas llevan hasta 16000 chars de contenido y las métricas no los necesitan.
   * Cada elemento es {@code [outcome (String), cantidad (Long)]}.
   */
  @Query("select t.outcome, count(t) from AgentTurn t "
      + "where t.kind = :kind and t.createdAt >= :from and t.createdAt < :to group by t.outcome")
  List<Object[]> countByOutcome(
      @Param("kind") String kind, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

  /** Cuántas filas de ese {@code kind} en la ventana [from, to) activaron el guard dado. */
  @Query("select count(t) from AgentTurn t "
      + "where t.kind = :kind and t.createdAt >= :from and t.createdAt < :to "
      + "and t.guards like concat('%', :guard, '%')")
  long countWithGuard(
      @Param("kind") String kind, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
      @Param("guard") String guard);
}
