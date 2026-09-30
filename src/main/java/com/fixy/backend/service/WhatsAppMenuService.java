package com.fixy.backend.service;

import com.fixy.backend.domain.CategoryDef;
import com.fixy.backend.domain.DomainCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Arma y envía el menú de apertura de WhatsApp: una interactive list con las
 * categorías MVP de Fixy, equivalente conversacional del formulario guiado
 * de la landing (ver spec "WhatsApp como canal de entrada").
 *
 * No declara qué categorías existen — eso vive en el catálogo de dominio
 * (domain/home-services.yml, ver DomainCatalog). Este servicio solo sabe cómo
 * mostrarlas en el formato de interactive list de Cloud API. Las descripciones
 * cortas por categoría (límite Meta: 72 caracteres) son el campo
 * {@code menuDescription} del YAML: no derivan de keywords (señales de
 * clasificación, no lecturas naturales) ni de intakeHint (pregunta completa de
 * seguimiento, pensada para el agente, no para una fila de menú) — es contenido
 * curado a mano, y afecta solo cómo se ve el menú, nunca clasificación ni matching.
 */
@Service
public class WhatsAppMenuService {

  private static final Logger log = LoggerFactory.getLogger(WhatsAppMenuService.class);

  /** Fila de escape para quien prefiere no usar el menú. No es una categoría
   * del catálogo — es una salida explícita hacia el texto libre. */
  private static final String OTHER_ROW_ID = "otro";

  private final WhatsAppService whatsappService;
  private final boolean enabled;
  private final String buttonLabel;

  public WhatsAppMenuService(
      WhatsAppService whatsappService,
      @Value("${fixy.whatsapp.menu.enabled:true}") boolean enabled,
      @Value("${fixy.whatsapp.menu.button-label:Ver opciones}") String buttonLabel
  ) {
    this.whatsappService = whatsappService;
    this.enabled = enabled;
    this.buttonLabel = buttonLabel;
  }

  /** Kill-switch propio del menú, independiente de WhatsApp en general: si
   * algo sale mal con la lista interactiva, se puede volver al saludo de
   * texto plano sin tocar código (fixy.whatsapp.menu.enabled=false). */
  public boolean isEnabled() {
    return enabled && whatsappService.isEnabled();
  }

  /**
   * Envía el menú de apertura al número dado. Devuelve false (no-op) si el
   * menú o WhatsApp están deshabilitados, o si Meta rechaza el envío — en
   * cualquier caso el llamador (LeadAgentService.greet) debe caer al saludo
   * de texto plano.
   */
  public boolean sendOpeningMenu(String toRaw) {
    if (!isEnabled()) {
      return false;
    }
    List<WhatsAppService.ListRow> rows = new ArrayList<>();
    for (CategoryDef category : DomainCatalog.get().mvpCategories()) {
      rows.add(new WhatsAppService.ListRow(
          category.id(),
          capitalize(category.label()),
          category.menuDescription() == null ? "" : category.menuDescription()));
    }
    rows.add(new WhatsAppService.ListRow(OTHER_ROW_ID, "Otro / escribir", "Contame con tus palabras"));

    boolean sent = whatsappService.sendInteractiveList(
        toRaw,
        "Hola, soy Fixy 👋 Elegí qué necesitás o escribime directo qué pasó.",
        buttonLabel,
        "Servicios",
        rows);
    if (!sent) {
      log.warn("menu de apertura: envio fallido a {}", toRaw);
    }
    return sent;
  }

  /** true si el id viene de una fila real del menú (categoría MVP o "otro"). */
  public static boolean isKnownRowId(String id) {
    if (OTHER_ROW_ID.equals(id)) {
      return true;
    }
    return DomainCatalog.get().categoryById(id).map(CategoryDef::mvp).orElse(false);
  }

  public static boolean isOtherRowId(String id) {
    return OTHER_ROW_ID.equals(id);
  }

  private static String capitalize(String text) {
    if (text == null || text.isBlank()) {
      return text;
    }
    return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
  }
}
