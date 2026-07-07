package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.UserLead;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.UserLeadRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Vincula leads anónimos (creados por accessToken) a un usuario logueado
 * con Google, y lista "Mis pedidos". La posesión del accessToken del lead
 * prueba propiedad — el cliente ya lo tiene en localStorage desde que creó
 * el lead como anónimo.
 */
@Service
public class UserLeadService {

  private final LeadRepository leadRepository;
  private final UserLeadRepository userLeadRepository;

  public UserLeadService(LeadRepository leadRepository, UserLeadRepository userLeadRepository) {
    this.leadRepository = leadRepository;
    this.userLeadRepository = userLeadRepository;
  }

  /** Vincula el lead al usuario si el accessToken matchea. Idempotente:
   * si ya estaba vinculado, no crea un duplicado. 403 si el token no
   * matchea, 404 si el lead no existe. */
  public void linkLead(Long userId, Long leadId, String accessToken) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "lead not found"));

    String actual = lead.getAccessToken();
    if (actual == null || accessToken == null || !actual.equals(accessToken)) {
      throw new ResponseStatusException(FORBIDDEN, "access token does not match this lead");
    }

    if (userLeadRepository.findByUserIdAndLeadId(userId, leadId).isPresent()) {
      return;
    }

    UserLead userLead = new UserLead();
    userLead.setUserId(userId);
    userLead.setLeadId(leadId);
    userLeadRepository.save(userLead);
  }

  /** Lista los leads vinculados al usuario, más recientes primero. */
  public List<Lead> listLeads(Long userId) {
    List<UserLead> links = userLeadRepository.findByUserIdOrderByCreatedAtDesc(userId);
    return links.stream()
        .map(link -> leadRepository.findById(link.getLeadId()))
        .flatMap(java.util.Optional::stream)
        .toList();
  }
}
