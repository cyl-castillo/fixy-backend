package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadPhotoResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.Provider;
import com.fixy.backend.service.LeadPhotoService;
import com.fixy.backend.service.ProviderSelfService;
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

/** Provider lee/sube fotos de leads que tiene asignados. */
@RestController
@RequestMapping("/api/public/providers/{providerId}/leads/{leadId}/photos")
public class PublicProviderPhotoController {

  private final ProviderSelfService selfService;
  private final LeadPhotoService photoService;

  public PublicProviderPhotoController(ProviderSelfService selfService, LeadPhotoService photoService) {
    this.selfService = selfService;
    this.photoService = photoService;
  }

  @GetMapping
  public List<LeadPhotoResponse> list(
      @PathVariable Long providerId,
      @PathVariable Long leadId,
      @RequestParam("token") String token
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    selfService.requireAssignedLead(provider, leadId);
    return photoService.listForLead(leadId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public LeadPhotoResponse upload(
      @PathVariable Long providerId,
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "caption", required = false) String caption
  ) {
    Provider provider = selfService.authenticate(providerId, token);
    Lead lead = selfService.requireAssignedLead(provider, leadId);
    return photoService.uploadAsProvider(lead.getId(), provider.getId(), file, caption);
  }
}
