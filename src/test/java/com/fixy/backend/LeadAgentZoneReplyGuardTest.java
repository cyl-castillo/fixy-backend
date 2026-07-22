package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.service.LeadAgentService;
import org.junit.jupiter.api.Test;

/**
 * Guard determinista de RESPUESTA (caso real lead #138, 2026-07-22): con
 * categoría conocida y zona faltante, el 8B contestó "Dale, aire
 * acondicionado en Lomas. ¿Qué tipo de servicio necesitás?" — zona alucinada
 * en el texto y repregunta de algo que el cliente ya había dicho, sin pedir
 * lo único que faltaba. Ese turno debe reemplazarse por el fallback
 * determinista (que reconoce lo que hay y pide solo la zona).
 */
class LeadAgentZoneReplyGuardTest {

  private static final String LEAD_138_CUSTOMER_MSG = "Necesito aire acondicionado: necesito limpieza y mantenimiento del aire";
  private static final String LEAD_138_BROKEN_REPLY = "Dale, aire acondicionado en Lomas. ¿Qué tipo de servicio necesitás? ¿Es instalación, service/limpieza o reparación?";

  @Test
  void caso138_categoriaConocidaZonaFaltanteYRespuestaQueNoPideZona_fuerzaFallback() {
    assertThat(LeadAgentService.shouldForceZoneQuestion(
        true, null, LEAD_138_CUSTOMER_MSG, LEAD_138_BROKEN_REPLY, false)).isTrue();
    assertThat(LeadAgentService.shouldForceZoneQuestion(
        true, "sin definir", LEAD_138_CUSTOMER_MSG, LEAD_138_BROKEN_REPLY, false)).isTrue();
  }

  @Test
  void siLaRespuestaSiPideLaZona_seDejaPasar() {
    assertThat(LeadAgentService.shouldForceZoneQuestion(
        true, null, LEAD_138_CUSTOMER_MSG, "¡Dale! ¿En qué zona estás así te busco un proveedor cerca?", false)).isFalse();
    // Insensible a acentos y a la forma de preguntar.
    assertThat(LeadAgentService.shouldForceZoneQuestion(
        true, null, LEAD_138_CUSTOMER_MSG, "Perfecto. ¿Dónde queda tu casa?", false)).isFalse();
    assertThat(LeadAgentService.shouldForceZoneQuestion(
        true, null, LEAD_138_CUSTOMER_MSG, "Genial, ¿de qué barrio sos?", false)).isFalse();
  }

  @Test
  void siElClienteEstaPreguntandoAlgo_elLlmPuedeResponderLibre() {
    assertThat(LeadAgentService.shouldForceZoneQuestion(
        true, null, "¿Trabajan los domingos?", "Sí, tenemos proveedores que trabajan los domingos.", false)).isFalse();
  }

  @Test
  void siLaZonaLlegoEnEsteTurnoOYaExiste_noAplica() {
    assertThat(LeadAgentService.shouldForceZoneQuestion(
        true, null, "estoy en Solymar", "Anotado, Solymar.", true)).isFalse();
    assertThat(LeadAgentService.shouldForceZoneQuestion(
        true, "Solymar", LEAD_138_CUSTOMER_MSG, LEAD_138_BROKEN_REPLY, false)).isFalse();
  }

  @Test
  void sinCategoriaConocida_noAplica() {
    assertThat(LeadAgentService.shouldForceZoneQuestion(
        false, null, "hola", "Contame qué necesitás.", false)).isFalse();
  }
}
