package com.fixy.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.LeadAssignmentService;
import com.fixy.backend.service.LeadMessageService;
import com.fixy.backend.service.LeadTimelineService;
import com.fixy.backend.service.WhatsAppInboundService;
import com.fixy.backend.service.WhatsAppService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook que recibe eventos de WhatsApp Cloud API:
 *  - GET: verificación inicial (hub.mode=subscribe + hub.verify_token + hub.challenge).
 *  - POST: notificaciones de mensajes entrantes y estados de envío.
 *
 * Cuando llega un mensaje del proveedor:
 *  - "SI" / "ACEPTO" / botón quick reply id "SI" → marca lead asignado +
 *    notifica al cliente en su chat web.
 *  - "NO" / "NO PUEDO" → libera el lead y posiblemente busca otro proveedor.
 *  - Texto libre → persiste como LeadMessage del proveedor; el cliente lo
 *    ve por polling en su chat web.
 *
 * Cuando llega un mensaje de un número que NO es un proveedor conocido, se
 * trata como CLIENTE: se delega a WhatsAppInboundService, que usa el mismo
 * cerebro agéntico (LeadAgentService) que atiende el chat web.
 *
 * Ventana de servicio de Meta (24h): solo podemos mandar texto libre en
 * respuesta a un mensaje que el usuario nos mandó dentro de las últimas 24h
 * (user-initiated). Todo lo que implementamos acá es user-initiated: el
 * cliente/proveedor escribe primero, nosotros respondemos. Mensajes
 * business-initiated (nosotros escribiendo primero fuera de esa ventana,
 * ej. avisarle a un proveedor de un lead nuevo sin que nos haya escrito)
 * requieren un template pre-aprobado por Meta (ver sendTemplate en
 * WhatsAppService) — no está cableado en el flujo de intake de este MVP,
 * queda para fase 2 (ver WhatsAppInboundService y FIXY_WHATSAPP_SETUP.md).
 */
@RestController
@RequestMapping("/api/webhooks/whatsapp")
public class WhatsAppWebhookController {

  private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

  private final ObjectMapper objectMapper;
  private final String verifyToken;
  private final String appSecret;
  private final ProviderRepository providerRepository;
  private final LeadRepository leadRepository;
  private final LeadMessageService leadMessageService;
  private final LeadTimelineService leadTimelineService;
  private final WhatsAppService whatsappService;
  private final WhatsAppInboundService whatsappInboundService;
  private final LeadAssignmentService leadAssignmentService;

  public WhatsAppWebhookController(
      ObjectMapper objectMapper,
      ProviderRepository providerRepository,
      LeadRepository leadRepository,
      LeadMessageService leadMessageService,
      LeadTimelineService leadTimelineService,
      WhatsAppService whatsappService,
      WhatsAppInboundService whatsappInboundService,
      LeadAssignmentService leadAssignmentService,
      @Value("${fixy.whatsapp.webhook-verify-token:}") String verifyToken,
      @Value("${fixy.whatsapp.app-secret:}") String appSecret
  ) {
    this.objectMapper = objectMapper;
    this.providerRepository = providerRepository;
    this.leadRepository = leadRepository;
    this.leadMessageService = leadMessageService;
    this.leadTimelineService = leadTimelineService;
    this.whatsappService = whatsappService;
    this.whatsappInboundService = whatsappInboundService;
    this.leadAssignmentService = leadAssignmentService;
    this.verifyToken = verifyToken;
    this.appSecret = appSecret;
  }

  @GetMapping
  public ResponseEntity<String> verify(
      @RequestParam(value = "hub.mode", required = false) String mode,
      @RequestParam(value = "hub.verify_token", required = false) String token,
      @RequestParam(value = "hub.challenge", required = false) String challenge
  ) {
    if ("subscribe".equals(mode) && verifyToken != null && !verifyToken.isBlank()
        && verifyToken.equals(token) && challenge != null) {
      log.info("whatsapp webhook verified");
      return ResponseEntity.ok(challenge);
    }
    log.warn("whatsapp webhook verify failed: mode={} token-match={}", mode,
        verifyToken != null && verifyToken.equals(token));
    return ResponseEntity.status(403).body("forbidden");
  }

  @PostMapping
  public ResponseEntity<String> receive(
      @RequestBody String rawBody,
      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature
  ) {
    if (!appSecret.isBlank() && !isValidSignature(rawBody, signature)) {
      log.warn("whatsapp webhook: firma invalida, se descarta el payload");
      return ResponseEntity.status(403).body("invalid signature");
    }
    // Devolvemos 200 siempre para que Meta no reintente. Procesamos best-effort.
    try {
      JsonNode root = objectMapper.readTree(rawBody);
      JsonNode entry = root.path("entry");
      if (entry.isArray()) {
        for (JsonNode e : entry) {
          JsonNode changes = e.path("changes");
          if (changes.isArray()) {
            for (JsonNode change : changes) {
              processChange(change.path("value"));
            }
          }
        }
      }
    } catch (Exception ex) {
      log.warn("whatsapp webhook process failed: {}", ex.getMessage());
    }
    return ResponseEntity.ok("OK");
  }

  private void processChange(JsonNode value) {
    JsonNode messages = value.path("messages");
    if (!messages.isArray() || messages.isEmpty()) return;
    for (JsonNode msg : messages) {
      handleMessage(msg);
    }
  }

  private void handleMessage(JsonNode msg) {
    String from = msg.path("from").asText("");
    if (from.isBlank()) return;
    String type = msg.path("type").asText("");

    String text = extractMessageText(msg, type);
    if (text == null || text.isBlank()) {
      log.info("whatsapp incoming from {} type={} sin texto, ignoramos", from, type);
      return;
    }

    Provider provider = providerRepository.findByContactNumber(WhatsAppService.denormalize(from))
        .or(() -> providerRepository.findByContactNumber(from))
        .orElse(null);
    if (provider == null) {
      // No es un proveedor conocido: lo tratamos como CLIENTE conversando
      // con el agente (mismo cerebro que el chat web).
      whatsappInboundService.handleCustomerMessage(from, text);
      return;
    }

    Lead lead = findActiveLeadForProvider(provider);
    if (lead == null) {
      log.info("whatsapp from provider {} sin lead activo: {}", provider.getName(), truncate(text, 60));
      return;
    }

    // Decisión rápida: aceptar / rechazar / texto libre.
    String norm = text.trim().toUpperCase().replaceAll("[¡!.,;:?¿]", "");
    if (isAcceptance(norm)) {
      acceptLead(lead, provider, text);
    } else if (isRejection(norm)) {
      rejectLead(lead, provider, text);
    } else {
      // Relay al cliente.
      leadMessageService.postFromOps(lead.getId(), "provider", text);
    }
  }

  private String extractMessageText(JsonNode msg, String type) {
    if ("text".equals(type)) {
      return msg.path("text").path("body").asText("");
    }
    if ("button".equals(type)) {
      return msg.path("button").path("text").asText("");
    }
    if ("interactive".equals(type)) {
      JsonNode interactive = msg.path("interactive");
      String iType = interactive.path("type").asText("");
      if ("button_reply".equals(iType)) {
        return interactive.path("button_reply").path("title").asText("");
      }
      if ("list_reply".equals(iType)) {
        return interactive.path("list_reply").path("title").asText("");
      }
    }
    return null;
  }

  private boolean isAcceptance(String upperNorm) {
    return upperNorm.equals("SI") || upperNorm.equals("SÍ") || upperNorm.startsWith("SI ")
        || upperNorm.equals("ACEPTO") || upperNorm.equals("OK") || upperNorm.equals("DALE")
        || upperNorm.startsWith("LO TOMO") || upperNorm.startsWith("ACEPT");
  }

  private boolean isRejection(String upperNorm) {
    return upperNorm.equals("NO") || upperNorm.startsWith("NO PUEDO") || upperNorm.startsWith("NO TENGO")
        || upperNorm.startsWith("NO ME") || upperNorm.equals("RECHAZO") || upperNorm.startsWith("RECHA");
  }

  private Lead findActiveLeadForProvider(Provider provider) {
    // Heurística simple: el lead más reciente con assigned_provider_id = provider.id
    // que NO esté en estado terminal. Si no lo encontramos, fallback al último lead
    // en PROVIDER_CONTACTED apuntando a este provider (por si todavía no se persistió
    // el assigned_provider_id por alguna razón).
    List<Lead> candidates = leadRepository.findAllByOrderByCreatedAtDesc();
    for (Lead lead : candidates) {
      Long assigned = lead.getAssignedProviderId();
      if (assigned != null && assigned.equals(provider.getId())
          && lead.getStatus() != LeadStatus.COMPLETED
          && lead.getStatus() != LeadStatus.CANCELLED) {
        return lead;
      }
    }
    return null;
  }

  private void acceptLead(Lead lead, Provider provider, String originalText) {
    Lead assigned;
    try {
      assigned = leadAssignmentService.acceptForProvider(lead.getId(), provider,
          provider.getName() + " aceptó por WhatsApp: " + truncate(originalText, 100));
    } catch (ResponseStatusException ex) {
      // Otro canal (bandeja u otro proveedor) ya se quedó con el lead entre
      // que WhatsApp mandó el mensaje y que lo procesamos acá.
      whatsappService.sendText(provider.getWhatsappNumber(),
          "Ese trabajo ya fue tomado, gracias igual.");
      return;
    }
    // Aviso al proveedor: confirmación de recibido (canal específico de WhatsApp).
    whatsappService.sendText(provider.getWhatsappNumber(),
        "Listo, ya le avisamos al cliente. Su tel: " + assigned.getPhone()
            + (assigned.getNotes() != null && !assigned.getNotes().isBlank() ? "\nNotas: " + assigned.getNotes() : ""));
  }

  private void rejectLead(Lead lead, Provider provider, String originalText) {
    lead.setAssignedProviderId(null);
    lead.setAssignedProvider(null);
    if (lead.getStatus() == LeadStatus.PROVIDER_CONTACTED) {
      lead.setStatus(LeadStatus.IN_REVIEW);
    }
    leadRepository.save(lead);
    leadTimelineService.appendEvent(lead, "PROVIDER_REJECTED", "provider",
        provider.getName() + " no puede: " + truncate(originalText, 100));
    leadMessageService.postFromOps(lead.getId(), "fixy",
        "El proveedor que contactamos no pudo tomarlo. Estamos buscando otro y te avisamos por acá.");
    // TODO: trigger tryAutoMatch del siguiente provider disponible (fase 2).
  }

  private String truncate(String text, int max) {
    if (text == null) return "";
    return text.length() > max ? text.substring(0, max) + "…" : text;
  }

  /**
   * Valida X-Hub-Signature-256: Meta firma el body raw con HMAC-SHA256 usando
   * el App Secret, formato header "sha256=<hex>". Comparación en tiempo
   * constante para evitar timing attacks.
   */
  private boolean isValidSignature(String rawBody, String signatureHeader) {
    if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
      return false;
    }
    String expectedHex = signatureHeader.substring("sha256=".length());
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] computed = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
      String computedHex = HexFormat.of().formatHex(computed);
      return MessageDigest.isEqual(
          computedHex.getBytes(StandardCharsets.UTF_8),
          expectedHex.getBytes(StandardCharsets.UTF_8));
    } catch (Exception ex) {
      log.warn("whatsapp webhook: fallo validando firma: {}", ex.getMessage());
      return false;
    }
  }
}
