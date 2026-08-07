package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadMessageCreateRequest;
import com.fixy.backend.dto.LeadMessageResponse;
import com.fixy.backend.service.CustomerMessageRouter;
import com.fixy.backend.service.LeadMessageService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/leads/{leadId}/messages")
public class PublicLeadMessageController {

  private final LeadMessageService messageService;
  private final CustomerMessageRouter messageRouter;

  public PublicLeadMessageController(
      LeadMessageService messageService,
      CustomerMessageRouter messageRouter
  ) {
    this.messageService = messageService;
    this.messageRouter = messageRouter;
  }

  @GetMapping
  public List<LeadMessageResponse> list(
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @RequestParam(value = "since", required = false) Long sinceId
  ) {
    if (sinceId != null) {
      return messageService.listSinceForCustomer(leadId, token, sinceId);
    }
    return messageService.listForCustomer(leadId, token);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public LeadMessageResponse create(
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @Valid @RequestBody LeadMessageCreateRequest request
  ) {
    LeadMessageResponse persisted = messageService.postFromCustomer(leadId, token, request.text());
    // Relay al proveedor aceptado o turno del agente — la regla vive en
    // CustomerMessageRouter (compartida con las notas de voz).
    messageRouter.route(leadId, request.text());
    return persisted;
  }
}
