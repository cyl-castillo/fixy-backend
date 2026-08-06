package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadCreateRequest;
import com.fixy.backend.dto.LeadEventResponse;
import com.fixy.backend.dto.LeadMatchResponse;
import com.fixy.backend.dto.LeadResponse;
import com.fixy.backend.dto.PublicChatStartRequest;
import com.fixy.backend.dto.PublicLeadContextUpdateRequest;
import com.fixy.backend.service.LeadAgentService;
import com.fixy.backend.service.LeadScheduleService;
import com.fixy.backend.service.LeadService;
import com.fixy.backend.service.LeadTimelineService;
import com.fixy.backend.service.PublicLeadAbuseProtectionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicLeadController {

  private final LeadService leadService;
  private final LeadTimelineService leadTimelineService;
  private final PublicLeadAbuseProtectionService abuseProtectionService;
  private final LeadScheduleService leadScheduleService;
  private final LeadAgentService leadAgentService;

  public PublicLeadController(
      LeadService leadService,
      LeadTimelineService leadTimelineService,
      PublicLeadAbuseProtectionService abuseProtectionService,
      LeadScheduleService leadScheduleService,
      LeadAgentService leadAgentService) {
    this.leadService = leadService;
    this.leadTimelineService = leadTimelineService;
    this.abuseProtectionService = abuseProtectionService;
    this.leadScheduleService = leadScheduleService;
    this.leadAgentService = leadAgentService;
  }

  @PostMapping("/leads")
  @ResponseStatus(HttpStatus.CREATED)
  public LeadResponse create(@Valid @RequestBody LeadCreateRequest request, HttpServletRequest httpRequest) {
    abuseProtectionService.validate(httpRequest.getRemoteAddr(), request.problem());
    return leadService.create(request);
  }

  /**
   * Inicia una conversacion chat-first. Crea un lead vacio y devuelve
   * leadId+accessToken. La conversacion arranca con un saludo del agente.
   */
  @PostMapping("/chats")
  @ResponseStatus(HttpStatus.CREATED)
  public LeadResponse startChat(@RequestBody(required = false) PublicChatStartRequest request, HttpServletRequest httpRequest) {
    abuseProtectionService.validate(httpRequest.getRemoteAddr(), "(chat start)");
    return leadService.createChat(request);
  }

  // Los endpoints por-lead exigen el accessToken del lead (query param, mismo
  // patrón que /messages, /photos y /confirm-completion). Sin token no hay
  // lectura ni mutación: los IDs son secuenciales y adivinables (IDOR).

  @GetMapping("/leads/{id}")
  public LeadResponse get(@PathVariable Long id, @RequestParam("token") String token) {
    leadService.requirePublicToken(id, token);
    return leadService.get(id);
  }

  @GetMapping("/leads/{id}/timeline")
  public List<LeadEventResponse> timeline(@PathVariable Long id, @RequestParam("token") String token) {
    leadService.requirePublicToken(id, token);
    return leadTimelineService.listForLead(id);
  }

  @PatchMapping("/leads/{id}/context")
  public LeadResponse updateContext(
      @PathVariable Long id,
      @RequestParam("token") String token,
      @RequestBody PublicLeadContextUpdateRequest request,
      HttpServletRequest httpRequest
  ) {
    leadService.requirePublicToken(id, token);
    abuseProtectionService.validateContextUpdate(
        httpRequest.getRemoteAddr(),
        request.problem(),
        request.notes(),
        request.location()
    );
    return leadService.updatePublicContext(id, request);
  }

  /**
   * "Horario acordado con un toque": el cliente responde la última propuesta
   * del proveedor (ver PublicProviderSelfController.scheduleProposal) con
   * Confirmar / No puedo — un toque, sin tipear. 409 si no hay propuesta
   * pendiente o ya fue respondida.
   */
  @PostMapping("/leads/{id}/schedule-response")
  public Map<String, Object> scheduleResponse(
      @PathVariable Long id,
      @RequestParam("token") String token,
      @Valid @RequestBody ScheduleResponseRequest request
  ) {
    leadService.requirePublicToken(id, token);
    return leadScheduleService.respond(id, Boolean.TRUE.equals(request.accept()));
  }

  public record ScheduleResponseRequest(@jakarta.validation.constraints.NotNull Boolean accept) {
  }

  @PostMapping("/leads/{id}/matches")
  public LeadMatchResponse generateMatches(@PathVariable Long id, @RequestParam("token") String token) {
    leadService.requirePublicToken(id, token);
    return leadService.generateMatches(id);
  }

  /**
   * "Hablar con una persona" (UX 2026-08): un toque desde el pedido escala
   * a humano — Telegram a ops + confirmación honesta en el chat. Ver
   * LeadAgentService.requestHumanHelp (idempotencias y guard de smoke).
   */
  @PostMapping("/leads/{id}/human-help")
  public Map<String, Object> requestHumanHelp(@PathVariable Long id, @RequestParam("token") String token) {
    leadService.requirePublicToken(id, token);
    leadAgentService.requestHumanHelp(id);
    return Map.of("ok", true);
  }
}
