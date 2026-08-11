package com.fixy.backend.controller;

import com.fixy.backend.dto.OfferCreateRequest;
import com.fixy.backend.dto.OfferResponse;
import com.fixy.backend.dto.OfferUpdateRequest;
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

  public OfferController(OfferService offerService) {
    this.offerService = offerService;
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

  /** Sube/reemplaza la foto de la oferta (multipart). Mismo patrón de storage que las fotos de lead. */
  @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public OfferResponse uploadPhoto(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
    return offerService.uploadPhoto(id, file);
  }
}
