package com.fixy.backend.controller;

import com.fixy.backend.dto.OfferCreateRequest;
import com.fixy.backend.dto.OfferDigestPreviewResponse;
import com.fixy.backend.dto.OfferDigestSendResponse;
import com.fixy.backend.dto.OfferInquiryResponse;
import com.fixy.backend.dto.OfferInquiryStatusUpdateRequest;
import com.fixy.backend.dto.OfferIngestRequest;
import com.fixy.backend.dto.OfferIngestResponse;
import com.fixy.backend.dto.OfferResponse;
import com.fixy.backend.dto.OfferUpdateRequest;
import com.fixy.backend.service.OfferDigestService;
import com.fixy.backend.service.OfferInquiryService;
import com.fixy.backend.service.OfferService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * CRUD admin de ofertas + cola de aprobación (mismo httpBasic + rol OPS que
 * /api/providers/**). {@code GET ?status=draft} ES la cola de aprobación —
 * el {@link OfferResponse} ya trae {@code sourceMessageRaw} y {@code photoUrl}
 * visibles, mismo patrón que {@code LeadController.list(status)}.
 */
@RestController
@RequestMapping("/api/offers")
public class OfferController {

  private final OfferService offerService;
  private final OfferDigestService offerDigestService;
  private final OfferInquiryService offerInquiryService;

  public OfferController(OfferService offerService, OfferDigestService offerDigestService,
      OfferInquiryService offerInquiryService) {
    this.offerService = offerService;
    this.offerDigestService = offerDigestService;
    this.offerInquiryService = offerInquiryService;
  }

  @GetMapping
  public List<OfferResponse> list(@RequestParam(required = false) String status) {
    return offerService.list(status);
  }

  @GetMapping("/{id}")
  public OfferResponse get(@PathVariable Long id) {
    return offerService.get(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OfferResponse create(@Valid @RequestBody OfferCreateRequest request) {
    return offerService.create(request);
  }

  @PatchMapping("/{id}")
  public OfferResponse update(@PathVariable Long id, @RequestBody OfferUpdateRequest request) {
    return offerService.update(id, request);
  }

  @PostMapping("/{id}/approve")
  public OfferResponse approve(@PathVariable Long id) {
    return offerService.approve(id);
  }

  @PostMapping("/{id}/reject")
  public OfferResponse reject(@PathVariable Long id) {
    return offerService.reject(id);
  }

  /**
   * Ingesta idempotente de la corrida diaria de scraping (ver
   * maquina/scripts/ofertas-fuentes/). Mismo httpBasic + rol OPS que el
   * resto de /api/offers/** — publicación sigue mediada por
   * aprobación humana, todo entra/actualiza en DRAFT.
   */
  @PostMapping("/ingest")
  public OfferIngestResponse ingest(@Valid @RequestBody OfferIngestRequest request) {
    return offerService.ingest(request);
  }

  /** Sube/reemplaza la foto de la oferta (multipart). Mismo patrón de storage que las fotos de lead. */
  @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public OfferResponse uploadPhoto(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
    return offerService.uploadPhoto(id, file);
  }

  /**
   * Preview del digest semanal de ofertas por zona (Fase Push-1): a quiénes
   * se les enviaría y por qué no a los demás, sin mandar nada — human-in-
   * the-loop antes de {@link #digestSend()}.
   */
  @GetMapping("/digest/preview")
  public OfferDigestPreviewResponse digestPreview() {
    return offerDigestService.preview();
  }

  /** Ejecuta el digest semanal de ofertas por zona (Fase Push-1) con las mismas reglas que el preview. */
  @PostMapping("/digest/send")
  public OfferDigestSendResponse digestSend() {
    return offerDigestService.send();
  }

  /** Drill-down admin de consultas de comercio por oferta (FIXY_OFERTAS_CTA_DESIGN.md §4.3). */
  @GetMapping("/{id}/inquiries")
  public List<OfferInquiryResponse> listInquiries(@PathVariable Long id) {
    return offerInquiryService.listForOffer(id);
  }

  /** Carlos tilda FORWARDED cuando ya reenvió la consulta al comercio a mano. */
  @PatchMapping("/{id}/inquiries/{inquiryId}")
  public OfferInquiryResponse updateInquiry(
      @PathVariable Long id,
      @PathVariable Long inquiryId,
      @RequestBody OfferInquiryStatusUpdateRequest request
  ) {
    return offerInquiryService.updateStatus(id, inquiryId, request);
  }
}
