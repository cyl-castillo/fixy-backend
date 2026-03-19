package com.fixy.backend.controller;

import com.fixy.backend.dto.IntakeRequest;
import com.fixy.backend.dto.IntakeResponse;
import com.fixy.backend.service.AgentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class IntakeController {

  private final AgentService agentService;

  public IntakeController(AgentService agentService) {
    this.agentService = agentService;
  }

  @PostMapping("/intake")
  @ResponseStatus(HttpStatus.OK)
  public IntakeResponse intake(@Valid @RequestBody IntakeRequest request) {
    return agentService.classify(request);
  }
}
