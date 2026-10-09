package com.fixy.backend.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fixy.backend.domain.fixy.HomeServicesTurnPolicy;
import com.fixy.backend.service.AgentService;
import com.fixy.backend.service.LeadMessageService;
import com.fixy.backend.service.WhatsAppMenuService;
import com.fixy.backend.service.WhatsAppService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * El cambio de garrafa de supergas tiene categoría propia (proveedor: una distribuidora, que no
 * debe recibir mandados) y es MVP: sin {@code mvp: true} {@code hasMatchingRequirements} nunca
 * la dejaría matchear.
 */
class GarrafaCategoryTest {

  private final DomainCatalog catalog = DomainCatalog.get();

  @Test
  void cambiarLaGarrafaEsGarrafaYElMandadoSigueSiendoMandado() {
    assertThat(catalog.detectCategory("necesito cambiar la garrafa").map(CategoryDef::id)).contains("garrafa");
    assertThat(catalog.detectCategory("hacer un mandado en la farmacia").map(CategoryDef::id))
        .contains("mandados");
  }

  @Test
  void laRecargaDeGasDelSplitSigueSiendoAire() {
    assertThat(catalog.detectCategory("recarga de gas del split").map(CategoryDef::id))
        .contains("aires_acondicionados");
  }

  @Test
  void garrafaEsCategoriaMvp() {
    HomeServicesTurnPolicy policy = new HomeServicesTurnPolicy(mock(LeadMessageService.class), mock(AgentService.class));
    assertThat(policy.isMvpCategory("garrafa")).isTrue();
    assertThat(WhatsAppMenuService.isKnownRowId("garrafa")).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void elMenuDeWhatsAppIncluyeLaFilaDeGarrafaYNoPasaElLimiteDeDiez() {
    WhatsAppService whatsappService = mock(WhatsAppService.class);
    when(whatsappService.isEnabled()).thenReturn(true);
    when(whatsappService.sendInteractiveList(anyString(), anyString(), anyString(), anyString(), anyList()))
        .thenReturn(true);

    new WhatsAppMenuService(whatsappService, true, "Ver opciones").sendOpeningMenu("59899123456");

    ArgumentCaptor<List<WhatsAppService.ListRow>> rows = ArgumentCaptor.forClass(List.class);
    verify(whatsappService).sendInteractiveList(anyString(), anyString(), anyString(), anyString(), rows.capture());
    assertThat(rows.getValue()).extracting(WhatsAppService.ListRow::id).contains("garrafa");
    assertThat(rows.getValue()).hasSizeLessThanOrEqualTo(10);
  }
}
