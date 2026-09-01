package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fixy.backend.model.ServiceCategory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit puro (sin contexto de Spring, sin red): el payload del menú de
 * apertura respeta los límites de Meta Cloud API para interactive list (10
 * filas por sección, 24 caracteres de título, 72 de descripción) y contiene
 * exactamente las categorías MVP de ServiceCategory + la fila de escape
 * "otro" — si se agrega una categoría MVP nueva y el conteo supera el
 * límite, este test debe romper antes que producción.
 */
class WhatsAppMenuServiceTest {

  @Test
  @SuppressWarnings("unchecked")
  void openingMenuContainsEveryMvpCategoryPlusEscapeRowWithinMetaLimits() {
    WhatsAppService whatsappService = mock(WhatsAppService.class);
    when(whatsappService.isEnabled()).thenReturn(true);
    when(whatsappService.sendInteractiveList(anyString(), anyString(), anyString(), anyString(), anyList()))
        .thenReturn(true);

    WhatsAppMenuService menuService = new WhatsAppMenuService(whatsappService, true, "Ver opciones");
    boolean sent = menuService.sendOpeningMenu("59899123456");
    assertThat(sent).isTrue();

    ArgumentCaptor<List<WhatsAppService.ListRow>> rowsCaptor = ArgumentCaptor.forClass(List.class);
    verify(whatsappService).sendInteractiveList(
        eq("59899123456"), anyString(), eq("Ver opciones"), anyString(), rowsCaptor.capture());
    List<WhatsAppService.ListRow> rows = rowsCaptor.getValue();

    assertThat(rows.size()).isLessThanOrEqualTo(10);
    assertThat(rows.size()).isEqualTo(ServiceCategory.MVP_IDS.size() + 1);
    assertThat(rows).extracting(WhatsAppService.ListRow::id)
        .containsAll(ServiceCategory.MVP_IDS)
        .contains("otro");

    for (WhatsAppService.ListRow row : rows) {
      assertThat(row.title()).as("título de la fila %s", row.id()).hasSizeLessThanOrEqualTo(24);
      if (row.description() != null) {
        assertThat(row.description()).as("descripción de la fila %s", row.id()).hasSizeLessThanOrEqualTo(72);
      }
    }
  }

  @Test
  void menuDisabledBySwitchNeverSends() {
    WhatsAppService whatsappService = mock(WhatsAppService.class);
    when(whatsappService.isEnabled()).thenReturn(true);
    WhatsAppMenuService menuService = new WhatsAppMenuService(whatsappService, false, "Ver opciones");

    assertThat(menuService.isEnabled()).isFalse();
    assertThat(menuService.sendOpeningMenu("59899123456")).isFalse();
  }

  @Test
  void menuDisabledWhenWhatsappItselfIsDisabled() {
    WhatsAppService whatsappService = mock(WhatsAppService.class);
    when(whatsappService.isEnabled()).thenReturn(false);
    WhatsAppMenuService menuService = new WhatsAppMenuService(whatsappService, true, "Ver opciones");

    assertThat(menuService.isEnabled()).isFalse();
    assertThat(menuService.sendOpeningMenu("59899123456")).isFalse();
  }

  @Test
  void knownRowIdRecognizesMvpCategoriesAndEscapeRowOnly() {
    assertThat(WhatsAppMenuService.isKnownRowId("plomeria")).isTrue();
    assertThat(WhatsAppMenuService.isKnownRowId("otro")).isTrue();
    assertThat(WhatsAppMenuService.isOtherRowId("otro")).isTrue();
    assertThat(WhatsAppMenuService.isOtherRowId("plomeria")).isFalse();
    // "electricidad" existe en ServiceCategory pero no es MVP: no debe
    // ofrecerse como fila del menú (ver decisión abierta en la spec).
    assertThat(WhatsAppMenuService.isKnownRowId("electricidad")).isFalse();
    assertThat(WhatsAppMenuService.isKnownRowId("algo-inventado")).isFalse();
  }
}
