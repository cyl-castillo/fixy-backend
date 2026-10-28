package com.fixy.backend.controller;

import com.fixy.backend.dto.BarrioDtos.AiAgreement;
import com.fixy.backend.dto.BarrioDtos.ApproveResponse;
import com.fixy.backend.dto.BarrioDtos.ClosureReviewResponse;
import com.fixy.backend.dto.BarrioDtos.OpsPending;
import com.fixy.backend.dto.BarrioDtos.OpsReport;
import com.fixy.backend.dto.BarrioDtos.PatchRequest;
import com.fixy.backend.dto.BarrioDtos.RejectRequest;
import com.fixy.backend.dto.BarrioDtos.AiSettings;
import com.fixy.backend.dto.BarrioDtos.AiSettingsRequest;
import com.fixy.backend.dto.BarrioDtos.UnpublishRequest;
import com.fixy.backend.service.BarrioService;
import com.fixy.backend.service.BarrioSettingsService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Moderación de Fixy Barrio (B1). Protegido por Basic auth de ops vía {@code /api/ops/**}. */
@RestController
@RequestMapping("/api/ops/barrio")
public class OpsBarrioController {

  private final BarrioService barrio;
  private final BarrioSettingsService settings;

  public OpsBarrioController(BarrioService barrio, BarrioSettingsService settings) {
    this.barrio = barrio;
    this.settings = settings;
  }

  @GetMapping("/pending")
  public OpsPending pending() {
    return barrio.pending();
  }

  @GetMapping("/ai-agreement")
  public AiAgreement aiAgreement() {
    return barrio.aiAgreement();
  }

  @GetMapping("/ai-settings")
  public AiSettings aiSettings() {
    return currentSettings();
  }

  @PutMapping("/ai-settings")
  public AiSettings putAiSettings(@RequestBody AiSettingsRequest body) {
    if (body == null || body.autoPublish() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "autoPublish es obligatorio");
    }
    settings.setAutoPublish(body.autoPublish());
    return currentSettings();
  }

  @GetMapping("/auto-published")
  public List<OpsReport> autoPublished() {
    return barrio.autoPublished();
  }

  @PostMapping("/reports/{id}/confirm")
  public ApproveResponse confirm(@PathVariable Long id) {
    return barrio.confirm(id);
  }

  @PostMapping("/reports/{id}/unpublish")
  public ApproveResponse unpublish(@PathVariable Long id, @RequestBody(required = false) UnpublishRequest body) {
    return barrio.unpublish(id, body == null ? null : body.reason());
  }

  private AiSettings currentSettings() {
    return new AiSettings(settings.autoPublish(), settings.minConfidence(), settings.dailyCap(),
        barrio.publishedTodayByAi());
  }

  @PostMapping("/reports/{id}/approve")
  public ApproveResponse approve(@PathVariable Long id) {
    return barrio.approve(id);
  }

  @PostMapping("/reports/{id}/reject")
  public ApproveResponse reject(@PathVariable Long id, @RequestBody(required = false) RejectRequest body) {
    return barrio.reject(id, body == null ? null : body.reason());
  }

  @PatchMapping("/reports/{id}")
  public OpsReport patch(@PathVariable Long id, @RequestBody PatchRequest body) {
    return barrio.patch(id, body.category(), body.lat(), body.lng());
  }

  @PostMapping("/closures/{id}/approve")
  public ClosureReviewResponse approveClosure(@PathVariable Long id) {
    return barrio.approveClosure(id);
  }

  @PostMapping("/closures/{id}/reject")
  public ClosureReviewResponse rejectClosure(@PathVariable Long id) {
    return barrio.rejectClosure(id);
  }
}
