package com.fixy.backend.service;

import com.fixy.backend.dto.RemoteCarePlanRequest;
import com.fixy.backend.dto.RemoteCarePlanRequestResponse;
import com.fixy.backend.model.CoverageZone;
import com.fixy.backend.model.RemoteCarePlan;
import com.fixy.backend.repository.RemoteCarePlanRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Alta pública del plan Casa a distancia (Refundación de Fixy, fase 2,
 * contrato §B.1/§B.3): {@code POST /api/public/remote-care/requests}. Mismo
 * esqueleto que {@code PublicOfferSubmissionService}: honeypot primero
 * (nunca delatar al bot), después validación + rate limit, y aviso a ops
 * por Telegram best-effort al final. El plan nace {@code REQUESTED} — ops lo
 * activa desde el admin.
 */
@Service
public class RemoteCareRequestService {

  private static final Logger log = LoggerFactory.getLogger(RemoteCareRequestService.class);
  private static final int NAME_MIN = 2;
  private static final int NAME_MAX = 200;
  private static final int ADDRESS_MIN = 4;
  private static final int ADDRESS_MAX = 255;

  private final RemoteCarePlanRepository remoteCarePlanRepository;
  private final PublicLeadAbuseProtectionService abuseProtectionService;
  private final TelegramNotifyService telegramNotifyService;
  private final int monthlyPrice;

  public RemoteCareRequestService(
      RemoteCarePlanRepository remoteCarePlanRepository,
      PublicLeadAbuseProtectionService abuseProtectionService,
      TelegramNotifyService telegramNotifyService,
      @Value("${fixy.remote-care.monthly-price:1900}") int monthlyPrice
  ) {
    this.remoteCarePlanRepository = remoteCarePlanRepository;
    this.abuseProtectionService = abuseProtectionService;
    this.telegramNotifyService = telegramNotifyService;
    this.monthlyPrice = monthlyPrice;
  }

  public RemoteCarePlanRequestResponse create(RemoteCarePlanRequest request, String clientIp) {
    if (hasText(request.website())) {
      // Honeypot: nunca delatar al bot — 201 con forma indistinguible de
      // una alta real, pero sin persistir nada (mismo contrato que
      // PublicOfferSubmissionService).
      return new RemoteCarePlanRequestResponse(0L, "");
    }

    abuseProtectionService.validateRemoteCareRequest(clientIp);

    if (!hasText(request.ownerName()) || request.ownerName().trim().length() < NAME_MIN
        || request.ownerName().trim().length() > NAME_MAX) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "ownerName es obligatorio (entre %d y %d caracteres)".formatted(NAME_MIN, NAME_MAX));
    }
    if (!hasText(request.phone())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phone es obligatorio");
    }
    CoverageZone zone = CoverageZone.fromLabel(request.zone())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "esa zona todavía no está en la cobertura de Fixy"));
    if (!hasText(request.address()) || request.address().trim().length() < ADDRESS_MIN
        || request.address().trim().length() > ADDRESS_MAX) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "address es obligatoria (entre %d y %d caracteres)".formatted(ADDRESS_MIN, ADDRESS_MAX));
    }

    RemoteCarePlan plan = new RemoteCarePlan();
    plan.setOwnerName(request.ownerName().trim());
    plan.setOwnerPhone(request.phone().trim());
    plan.setOwnerEmail(hasText(request.email()) ? request.email().trim() : null);
    plan.setPropertyZone(zone.label());
    plan.setPropertyAddress(request.address().trim());
    plan.setOnSiteName(hasText(request.onSiteName()) ? request.onSiteName().trim() : null);
    plan.setOnSitePhone(hasText(request.onSitePhone()) ? request.onSitePhone().trim() : null);
    plan.setNotes(hasText(request.notes()) ? request.notes().trim() : null);
    plan.setMonthlyPrice(monthlyPrice);
    plan.setAccessToken(UUID.randomUUID().toString().replace("-", ""));

    RemoteCarePlan saved = remoteCarePlanRepository.save(plan);

    try {
      telegramNotifyService.notifyRemoteCarePlanRequested(saved);
    } catch (Exception ex) {
      log.warn("telegram notify remote-care-plan-requested planId={} failed: {}", saved.getId(), ex.getMessage());
    }

    return new RemoteCarePlanRequestResponse(saved.getId(), saved.getAccessToken());
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isBlank();
  }
}
