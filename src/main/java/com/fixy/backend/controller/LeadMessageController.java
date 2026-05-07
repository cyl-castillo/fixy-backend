package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadMessageResponse;
import com.fixy.backend.service.LeadMessageService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leads/{leadId}/messages")
public class LeadMessageController {

  private final LeadMessageService messageService;

  public LeadMessageController(LeadMessageService messageService) {
    this.messageService = messageService;
  }

  @GetMapping
  public List<LeadMessageResponse> list(@PathVariable Long leadId) {
    return messageService.listForOps(leadId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public LeadMessageResponse create(
      @PathVariable Long leadId,
      @RequestBody OpsMessageRequest request
  ) {
    return messageService.postFromOps(leadId, request.sender(), request.text());
  }

  public record OpsMessageRequest(
      @NotBlank String sender,
      @NotBlank @Size(max = 2000) String text
  ) {
  }
}
