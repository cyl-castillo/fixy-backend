package com.fixy.backend.repository;

import com.fixy.backend.model.LeadMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadMessageRepository extends JpaRepository<LeadMessage, Long> {

  List<LeadMessage> findByLeadIdOrderByCreatedAtAsc(Long leadId);

  List<LeadMessage> findByLeadIdAndIdGreaterThanOrderByCreatedAtAsc(Long leadId, Long sinceId);

  Optional<LeadMessage> findFirstByLeadIdOrderByIdDesc(Long leadId);

  /** Último mensaje de un remitente concreto, salteando lo que hayan dicho
   * los demás en el medio. Lo usa el guard anti-loro de
   * {@link com.fixy.backend.service.LeadMessageService#postFromAgent}: lo que
   * importa no es quién habló último en el chat, sino qué fue lo último que
   * dijo el AGENTE. */
  Optional<LeadMessage> findFirstByLeadIdAndSenderOrderByIdDesc(Long leadId, String sender);
}
