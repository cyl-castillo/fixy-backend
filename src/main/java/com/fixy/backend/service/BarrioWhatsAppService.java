package com.fixy.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixy.backend.dto.BarrioDtos.CreateResponse;
import com.fixy.backend.model.BarrioWaDraft;
import com.fixy.backend.repository.BarrioWaDraftRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Circuito Fixy Barrio por WhatsApp (B1): un borrador por teléfono junta categoría + foto +
 * ubicación en cualquier orden, pide lo que falta y, completo, crea el reclamo PENDING_REVIEW.
 * Solo atiende números que NO son proveedores del padrón (el controller ya lo filtró) y solo
 * responde dentro de la conversación (ventana de 24 h): nunca inicia mensajes.
 *
 * <p>Todo es determinista (sin LLM): los disparadores viven en {@link BarrioTriggers}.
 */
@Service
public class BarrioWhatsAppService {

  private static final Logger log = LoggerFactory.getLogger(BarrioWhatsAppService.class);

  static final Duration DRAFT_TTL = Duration.ofHours(2);
  static final String ROW_PREFIX = "barrio_";
  static final String ROW_OTHER = "barrio_otro";
  private static final Set<String> CANCEL_WORDS = Set.of("cancelar", "cancela", "cancelo", "descartar", "olvidalo", "olvidate");

  /** Qué hizo el circuito con el mensaje. */
  public enum Result {
    /** No es de Barrio: sigue el ruteo normal de Fixy. */
    NOT_HANDLED,
    /** Barrio lo atendió (y ya respondió). */
    HANDLED,
    /** "Otra cosa": el borrador se descartó y hay que invitar al vecino a contar su pedido a Fixy. */
    FREE_TEXT
  }

  private final BarrioWaDraftRepository drafts;
  private final BarrioService barrio;
  private final BarrioPhotoService photos;
  private final WhatsAppService whatsapp;
  private final String publicAppBaseUrl;

  public BarrioWhatsAppService(
      BarrioWaDraftRepository drafts,
      BarrioService barrio,
      BarrioPhotoService photos,
      WhatsAppService whatsapp,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl
  ) {
    this.drafts = drafts;
    this.barrio = barrio;
    this.photos = photos;
    this.whatsapp = whatsapp;
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
  }

  /**
   * @param type                tipo del mensaje de Meta (text, image, location, interactive, button…)
   * @param text                texto extraído por el controller (null si no hay)
   * @param interactiveReplyId  id de la fila/botón elegido, o null
   * @param activeConversation  el vecino tiene un pedido de Fixy abierto
   */
  public Result handle(String from, String type, JsonNode msg, String text, String interactiveReplyId,
      boolean activeConversation) {
    String phone = WhatsAppService.normalize(from);
    if (phone == null) return Result.NOT_HANDLED;
    BarrioWaDraft draft = loadDraft(phone).orElse(null);

    if (interactiveReplyId != null && interactiveReplyId.startsWith(ROW_PREFIX)) {
      return handleCategoryRow(phone, from, draft, interactiveReplyId);
    }
    if (interactiveReplyId != null) {
      return Result.NOT_HANDLED; // fila de otro menú (apertura de Fixy): no es de Barrio
    }
    if ("image".equals(type)) {
      if (draft == null && activeConversation) return Result.NOT_HANDLED;
      return handleImage(phone, from, draft, msg.path("image"));
    }
    if ("location".equals(type)) {
      if (draft == null && activeConversation) return Result.NOT_HANDLED;
      return handleLocation(phone, from, draft, msg.path("location"));
    }
    if (text == null || text.isBlank()) return Result.NOT_HANDLED;
    return handleText(phone, from, draft, text, activeConversation);
  }

  // ---------------------------------------------------------------- pasos

  private Result handleCategoryRow(String phone, String from, BarrioWaDraft draft, String rowId) {
    if (ROW_OTHER.equals(rowId)) {
      if (draft != null) discard(draft);
      return Result.FREE_TEXT;
    }
    String category = rowId.substring(ROW_PREFIX.length());
    if (!BarrioService.CATEGORIES.contains(category)) return Result.NOT_HANDLED;
    BarrioWaDraft d = draft != null ? draft : newDraft(phone);
    d.setCategory(category);
    return advance(d, from);
  }

  private Result handleImage(String phone, String from, BarrioWaDraft draft, JsonNode image) {
    String mediaId = image.path("id").asText("");
    WhatsAppService.MediaDownload media = whatsapp.downloadMedia(mediaId);
    if (media == null) {
      whatsapp.sendText(from, "No pude abrir la foto. ¿Me la mandás de nuevo?");
      return Result.HANDLED;
    }
    String stored;
    try {
      stored = photos.store(media.bytes());
    } catch (ResponseStatusException e) {
      whatsapp.sendText(from, "Esa imagen no la pude leer. ¿Me mandás otra foto?");
      return Result.HANDLED;
    }
    BarrioWaDraft d = draft != null ? draft : newDraft(phone);
    if (d.hasPhoto()) photos.deleteQuietly(d.getPhotoPath());
    d.setPhotoPath(stored);
    if (d.getCategory() == null) {
      BarrioTriggers.parse(image.path("caption").asText("")).ifPresent(m -> d.setCategory(m.category()));
    }
    return advance(d, from);
  }

  private Result handleLocation(String phone, String from, BarrioWaDraft draft, JsonNode location) {
    if (!location.path("latitude").isNumber() || !location.path("longitude").isNumber()) {
      return Result.NOT_HANDLED;
    }
    double lat = location.path("latitude").asDouble();
    double lng = location.path("longitude").asDouble();
    if (!BarrioGeo.inCoverage(lat, lng)) {
      whatsapp.sendText(from, "Por ahora el mapa del barrio solo cubre Ciudad de la Costa. "
          + "Si el problema está acá, mandame la ubicación del lugar (📎 → Ubicación).");
      return Result.HANDLED;
    }
    BarrioWaDraft d = draft != null ? draft : newDraft(phone);
    d.setLat(lat);
    d.setLng(lng);
    return advance(d, from);
  }

  private Result handleText(String phone, String from, BarrioWaDraft draft, String text, boolean activeConversation) {
    Optional<BarrioTriggers.Match> match = BarrioTriggers.parse(text);
    if (draft != null) {
      if (CANCEL_WORDS.contains(BarrioTriggers.normalize(text).trim())) {
        discard(draft);
        whatsapp.sendText(from, "Listo, descarté el reclamo. Cuando quieras reportar algo, escribime de nuevo.");
        return Result.HANDLED;
      }
      match.ifPresent(m -> draft.setCategory(m.category()));
      return advance(draft, from);
    }
    if (match.isEmpty()) return Result.NOT_HANDLED;
    // poda/tala/ramas/troncos también son un pedido de jardinería: con un pedido de Fixy abierto
    // el mensaje sigue su camino de siempre.
    if (match.get().ambiguous() && activeConversation) return Result.NOT_HANDLED;
    BarrioWaDraft d = newDraft(phone);
    d.setCategory(match.get().category());
    return advance(d, from);
  }

  /** Completo → crea el reclamo; si no, guarda el borrador y pide lo que falta. */
  private Result advance(BarrioWaDraft d, String from) {
    boolean hasCategory = d.getCategory() != null;
    if (hasCategory && d.hasPhoto() && d.hasLocation()) {
      try {
        CreateResponse created = barrio.create(d.getCategory(), d.getLat(), d.getLng(), d.getPhotoPath(),
            null, d.getPhone(), "whatsapp");
        if (d.getId() != null) drafts.delete(d);
        whatsapp.sendText(from, "Recibido, reclamo #%d. Lo revisamos y en unas horas aparece en el mapa: %s/barrio/r/%d"
            .formatted(created.id(), publicAppBaseUrl, created.id()));
      } catch (RuntimeException e) {
        log.warn("barrio whatsapp: no se pudo crear el reclamo del borrador {}: {}", d.getPhone(), e.getMessage());
        save(d);
        whatsapp.sendText(from, "Tuve un problema guardando tu reclamo. Escribime de nuevo en un rato, por favor.");
      }
      return Result.HANDLED;
    }
    save(d);
    if (!hasCategory) {
      askCategory(from);
    } else if (!d.hasPhoto() && !d.hasLocation()) {
      whatsapp.sendText(from, "Anotado: " + BarrioService.labelOf(d.getCategory()).toLowerCase()
          + ". Mandame una foto y la ubicación del lugar (📎 → Ubicación). Si lo querés dejar, escribí \"cancelar\".");
    } else if (!d.hasPhoto()) {
      whatsapp.sendText(from, "Falta la foto. Mandame una foto del problema, por favor.");
    } else {
      whatsapp.sendText(from, "Falta la ubicación. Tocá 📎 → Ubicación y mandame el punto donde está el problema.");
    }
    return Result.HANDLED;
  }

  private void askCategory(String from) {
    boolean sent = whatsapp.sendInteractiveList(from,
        "Para reportarlo en el mapa del barrio necesito saber qué es. Elegí una opción.",
        "Elegir", "Tipo de reclamo",
        List.of(
            new WhatsAppService.ListRow(ROW_PREFIX + BarrioTriggers.BACHE, "Bache", "Pozo en la calle"),
            new WhatsAppService.ListRow(ROW_PREFIX + BarrioTriggers.PODA, "Restos de poda", "Ramas o troncos en la calle"),
            new WhatsAppService.ListRow(ROW_PREFIX + BarrioTriggers.LUMINARIA, "Luminaria apagada", "Foco de la calle sin luz"),
            new WhatsAppService.ListRow(ROW_OTHER, "Otra cosa", "Es otro pedido")));
    if (!sent) {
      whatsapp.sendText(from, "¿Qué querés reportar? Escribime \"bache\", \"poda\" o \"luminaria\".");
    }
  }

  // ------------------------------------------------------------- borrador

  private Optional<BarrioWaDraft> loadDraft(String phone) {
    Optional<BarrioWaDraft> found = drafts.findByPhone(phone);
    if (found.isPresent() && found.get().getUpdatedAt().isBefore(OffsetDateTime.now().minus(DRAFT_TTL))) {
      discard(found.get());
      return Optional.empty();
    }
    return found;
  }

  private BarrioWaDraft newDraft(String phone) {
    BarrioWaDraft d = new BarrioWaDraft();
    d.setPhone(phone);
    return d;
  }

  private void save(BarrioWaDraft d) {
    d.setUpdatedAt(OffsetDateTime.now());
    drafts.save(d);
  }

  private void discard(BarrioWaDraft d) {
    photos.deleteQuietly(d.getPhotoPath());
    if (d.getId() != null) drafts.delete(d);
  }
}
