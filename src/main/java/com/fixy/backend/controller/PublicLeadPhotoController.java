package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadPhotoResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.service.LeadPhotoService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Cliente lee y sube fotos de su lead, autenticado con el lead accessToken. */
@RestController
@RequestMapping("/api/public/leads/{leadId}/photos")
public class PublicLeadPhotoController {

  private final LeadPhotoService photoService;
  private final LeadRepository leadRepository;

  public PublicLeadPhotoController(LeadPhotoService photoService, LeadRepository leadRepository) {
    this.photoService = photoService;
    this.leadRepository = leadRepository;
  }

  @GetMapping
  public List<LeadPhotoResponse> list(
      @PathVariable Long leadId,
      @RequestParam("token") String token
  ) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    if (lead.getAccessToken() == null || token == null || !lead.getAccessToken().equals(token)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid token");
    }
    return photoService.listForLead(leadId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public LeadPhotoResponse upload(
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "caption", required = false) String caption
  ) {
    return photoService.uploadAsCustomer(leadId, token, file, caption);
  }
}
