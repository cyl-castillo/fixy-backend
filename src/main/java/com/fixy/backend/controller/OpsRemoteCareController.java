package com.fixy.backend.controller;

import com.fixy.backend.dto.OrderCreateResponse;
import com.fixy.backend.dto.RemoteCarePlanSummary;
import com.fixy.backend.service.RemoteCareService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Panel ops "Casa a distancia" (contrato §B.3/§B.5). Mismo esquema de auth
 * que el resto de /api/ops/** (SecurityConfig: básica de ops).
 */
@RestController
@RequestMapping("/api/ops/remote-care")
public class OpsRemoteCareController {

  private final RemoteCareService remoteCareService;

  public OpsRemoteCareController(RemoteCareService remoteCareService) {
    this.remoteCareService = remoteCareService;
  }

  @GetMapping
  public List<RemoteCarePlanSummary> list() {
    return remoteCareService.list();
  }

  @PostMapping("/{id}/activate")
  public RemoteCarePlanSummary activate(@PathVariable Long id) {
    return remoteCareService.activate(id);
  }

  @PostMapping("/{id}/pause")
  public RemoteCarePlanSummary pause(@PathVariable Long id) {
    return remoteCareService.pause(id);
  }

  @PostMapping("/{id}/cancel")
  public RemoteCarePlanSummary cancel(@PathVariable Long id) {
    return remoteCareService.cancel(id);
  }

  @PatchMapping("/{id}/note")
  public RemoteCarePlanSummary note(@PathVariable Long id, @RequestBody NoteRequest request) {
    return remoteCareService.updateNote(id, request.nextVisitNote());
  }

  @PostMapping("/{id}/visit")
  @ResponseStatus(HttpStatus.CREATED)
  public OrderCreateResponse visit(@PathVariable Long id) {
    return remoteCareService.createVisit(id);
  }

  @GetMapping("/{id}/link")
  public Map<String, String> link(@PathVariable Long id) {
    return Map.of("link", remoteCareService.linkFor(id));
  }

  public record NoteRequest(String nextVisitNote) {
  }
}
