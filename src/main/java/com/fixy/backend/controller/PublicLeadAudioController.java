package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadMessageResponse;
import com.fixy.backend.service.LeadVoiceNoteService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Nota de voz del cliente: sube el audio autenticado con el accessToken del
 * lead (mismo patrón que fotos y mensajes). La respuesta es el mensaje ya
 * creado en el chat, con la transcripción como text y el audio en audioUrl.
 */
@RestController
@RequestMapping("/api/public/leads/{leadId}/audio")
public class PublicLeadAudioController {

  private final LeadVoiceNoteService voiceNoteService;

  public PublicLeadAudioController(LeadVoiceNoteService voiceNoteService) {
    this.voiceNoteService = voiceNoteService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public LeadMessageResponse upload(
      @PathVariable Long leadId,
      @RequestParam("token") String token,
      @RequestParam("file") MultipartFile file
  ) {
    return voiceNoteService.receiveFromCustomer(leadId, token, file);
  }
}
