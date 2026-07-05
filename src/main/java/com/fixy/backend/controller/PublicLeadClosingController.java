package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadCompletionConfirmRequest;
import com.fixy.backend.dto.LeadCompletionConfirmResponse;
import com.fixy.backend.service.LeadClosingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * H2.1: confirmación pública del cliente sobre un trabajo COMPLETED,
 * autenticada por el accessToken del LEAD (mismo patrón que
 * {@link PublicLeadMessageController}) — deliberadamente distinto del
 * token de provider, para que el proveedor no pueda confirmar en su
 * propio nombre.
 */
@RestController
@RequestMapping("/api/public/leads/{leadId}")
public class PublicLeadClosingController {

  private final LeadClosingService leadClosingService;

  public PublicLeadClosingController(LeadClosingService leadClosingService) {
    this.leadClosingService = leadClosingService;
  }

  @PostMapping("/confirm-completion")
  public LeadCompletionConfirmResponse confirmCompletion(
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @Valid @RequestBody LeadCompletionConfirmRequest request
  ) {
    return leadClosingService.confirmCompletion(leadId, token, request);
  }
}
