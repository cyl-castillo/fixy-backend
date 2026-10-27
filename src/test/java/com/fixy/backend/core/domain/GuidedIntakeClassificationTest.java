package com.fixy.backend.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.domain.fixy.HomeServicesPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Fase 1 (2026-10-27): el home ofrece tortas, decoración y mandados por el
 * paso a paso guiado (fixy-app guidedIntake.ts). El primer mensaje que arma
 * el asistente tiene que clasificar solo por vocabulario, sin LLM, y el
 * WhatsApp que agrega al final tiene que ser extraíble por regex.
 */
class GuidedIntakeClassificationTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "Necesito pastelería: quiero una torta de cumpleaños, en Solymar. Mi WhatsApp es 099123456|pasteleria",
      "Necesito pastelería: quiero una mesa dulce, en Lagomar. Mi WhatsApp es 099123456|pasteleria",
      "Necesito pastelería: quiero una torta para un evento, en El Pinar. Mi WhatsApp es 099123456|pasteleria",
      "Necesito decoración de fiesta: quiero decoración para un cumpleaños infantil, en Solymar. Mi WhatsApp es 099123456|decoracion_fiestas",
      "Necesito decoración de fiesta: quiero ambientación con globos, en Lagomar. Mi WhatsApp es 099123456|decoracion_fiestas",
      "Necesito decoración de fiesta: quiero decoración para un evento, en Shangrilá. Mi WhatsApp es 099123456|decoracion_fiestas",
      "Necesito un mandado: necesito la compra del supermercado, en Lagomar. Mi WhatsApp es 099123456|mandados",
      "Necesito un mandado: necesito un mandado a la farmacia, en Solymar. Mi WhatsApp es 099123456|mandados",
      "Necesito un mandado: necesito pagar unas facturas en Abitab o Redpagos, en El Pinar. Mi WhatsApp es 099123456|mandados",
  })
  void elMensajeDelPasoAPasoClasificaSinLlm(String message, String expectedCategory) {
    assertThat(DomainCatalog.get().detectCategory(message).map(CategoryDef::id)).contains(expectedCategory);
  }

  @Test
  void elWhatsAppDelPasoAPasoSeExtraeDelMensaje() {
    assertThat(HomeServicesPolicy.phoneMentionedIn(
        "Necesito pastelería: quiero una torta de cumpleaños, en Solymar. Mi WhatsApp es 099123456"))
        .isEqualTo("099123456");
  }
}
