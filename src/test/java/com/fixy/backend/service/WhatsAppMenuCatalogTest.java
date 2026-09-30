package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Core Fase 2: el menú de apertura se arma desde {@code DomainCatalog.mvpCategories()} con la
 * {@code menuDescription} del YAML; las filas (id, título, descripción) son idénticas a las de
 * antes de mover las descripciones fuera de {@code WhatsAppMenuService}.
 */
class WhatsAppMenuCatalogTest {

  @Test
  @SuppressWarnings("unchecked")
  void lasFilasDelMenuSonLasDeSiempre() {
    WhatsAppService whatsappService = mock(WhatsAppService.class);
    when(whatsappService.isEnabled()).thenReturn(true);
    when(whatsappService.sendInteractiveList(anyString(), anyString(), anyString(), anyString(), anyList()))
        .thenReturn(true);

    new WhatsAppMenuService(whatsappService, true, "Ver opciones").sendOpeningMenu("59899123456");

    ArgumentCaptor<List<WhatsAppService.ListRow>> rows = ArgumentCaptor.forClass(List.class);
    verify(whatsappService).sendInteractiveList(anyString(), anyString(), anyString(), anyString(), rows.capture());
    assertThat(rows.getValue()).extracting(r -> r.id() + "|" + r.title() + "|" + r.description())
        .containsExactly(
            "plomeria|Plomería|Pérdidas, canillas tapadas, destapes",
            "barometrica|Barométrica|Pozos y cámaras sépticas",
            "jardineria|Jardinería|Corte de pasto, poda, mantenimiento",
            "aires_acondicionados|Aire acondicionado|Instalación, service, no enfría o no calienta",
            "pasteleria|Pastelería|Tortas, cumpleaños, mesa dulce",
            "decoracion_fiestas|Decoración de fiestas|Globos, ambientación, decoración de eventos",
            "mandados|Mandados y trámites|Súper, farmacia, trámites y pagos",
            "otro|Otro / escribir|Contame con tus palabras");
  }
}
