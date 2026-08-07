package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Pedido de Carlos 2026-07-30 (pensando en personas mayores): si el cliente
 * se equivoca de categoría, corregirlo charlando tiene que funcionar.
 * Mientras el pedido no tenga proveedor contactado, la corrección natural
 * ("me equivoqué, es jardinería") actualiza categoría y zona; después del
 * matching no se pisa en silencio.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Agente prendido sin credenciales → fallback heurístico determinista
// (mismas props que LeadAgentFallbackTest / SmokeTagPreservationTest).
@org.springframework.test.context.TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
})
class LeadCategoryCorrectionTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private LeadRepository leadRepository;

  private record ChatSession(Integer leadId, String token) {}

  private ChatSession openChat() throws Exception {
    MvcResult chat = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"channel\":\"web-chat\"}"))
        .andExpect(status().is2xxSuccessful())
        .andReturn();
    return new ChatSession(
        JsonPath.read(chat.getResponse().getContentAsString(), "$.id"),
        JsonPath.read(chat.getResponse().getContentAsString(), "$.accessToken"));
  }

  private void say(ChatSession s, String text) throws Exception {
    mockMvc.perform(post("/api/public/leads/{id}/messages", s.leadId())
            .param("token", s.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"" + text + "\"}"))
        .andExpect(status().isCreated());
  }

  private void awaitCategory(ChatSession s, String expected) {
    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(
            leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow().getDetectedCategory())
            .isEqualTo(expected));
  }

  @Test
  void corregirLaCategoriaCharlandoFuncionaAntesDelMatching() throws Exception {
    // Pastelería en Solymar NO tiene proveedor seed (ver ProviderSeedConfig):
    // el lead queda NEW sin asignar — la ventana exacta de corrección.
    ChatSession s = openChat();
    say(s, "[smoke] Quiero una torta para un cumpleaños, en Solymar");
    awaitCategory(s, "pasteleria");

    say(s, "[smoke] Perdón, me equivoqué: en realidad quiero decoración con globos para la fiesta");
    awaitCategory(s, "decoracion_fiestas");
  }

  @Test
  void laCorreccionRedisparaElMatchingParaLaCategoriaNueva() throws Exception {
    // Pastelería en Lagomar: sin proveedor → NEW. Corrección a jardinería:
    // el seed "Jardines del Este" cubre Lagomar — el re-matching tiene que
    // contactarlo (sin re-disparo el pedido corregido quedaba NEW aunque
    // hubiera proveedor, visto en prod con pastelería→decoración).
    ChatSession s = openChat();
    say(s, "[smoke] Quiero una torta para un cumpleaños, en Lagomar");
    awaitCategory(s, "pasteleria");

    say(s, "[smoke] Perdón, me equivoqué: es jardinería, cortar el pasto");
    awaitCategory(s, "jardineria");
    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(
            leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow().getStatus())
            .isEqualTo(LeadStatus.PROVIDER_CONTACTED));
  }

  @Test
  void conProveedorContactadoLaCategoriaNoSePisaEnSilencio() throws Exception {
    // Plomería en Solymar SÍ tiene proveedor seed: el automatch real deja el
    // lead PROVIDER_CONTACTED — desde ahí la corrección silenciosa se bloquea.
    ChatSession s = openChat();
    say(s, "[smoke] Necesito plomería: pérdida de agua, en Solymar");
    awaitCategory(s, "plomeria");
    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(
            leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow().getStatus())
            .isEqualTo(LeadStatus.PROVIDER_CONTACTED));

    say(s, "[smoke] Ahora que lo pienso también tengo el jardín hecho un desastre, cortar el pasto");
    // Dar tiempo al turno del agente y verificar que la categoría NO cambió.
    Thread.sleep(3000);
    assertThat(leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow().getDetectedCategory())
        .isEqualTo("plomeria");
  }

  /**
   * Prueba real de Carlos 2026-08-07 (lead #235, al banco como las de
   * #194/#196): pidió mandados y la nota de voz "quiero agua en el Tata"
   * (transcripta "agua enlatada") re-clasificó el pedido a plomería. La
   * lista de un mandado SIEMPRE nombra productos que son keywords de otras
   * categorías — mencionar una keyword suelta NO es corregir.
   */
  @Test
  void laListaDelMandadoNoCambiaLaCategoriaAunqueNombreProductos() throws Exception {
    ChatSession s = openChat();
    say(s, "[smoke] Necesito un mandado: la compra del supermercado, en Lomas de Solymar");
    awaitCategory(s, "mandados");

    // El caso exacto de la prueba (transcripción incluida): "agua" es
    // keyword de plomería, "torta" de pastelería — nada de eso corrige.
    say(s, "[smoke] Hola, necesito comprar agua enlatada.");
    Thread.sleep(3000);
    assertThat(leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow().getDetectedCategory())
        .isEqualTo("mandados");

    say(s, "[smoke] y también una torta de cumpleaños del Tata y yerba");
    Thread.sleep(3000);
    assertThat(leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow().getDetectedCategory())
        .isEqualTo("mandados");
  }

  @Test
  void laCorreccionExplicitaSigueFuncionandoDespuesDelGate() throws Exception {
    // El gate no puede matar la corrección real: mandados → "me equivoqué,
    // es plomería" tiene que seguir cambiando la categoría.
    ChatSession s = openChat();
    say(s, "[smoke] Necesito un mandado: la compra del supermercado, en Lomas de Solymar");
    awaitCategory(s, "mandados");

    say(s, "[smoke] Perdón, me equivoqué: es plomería, tengo una pérdida de agua");
    awaitCategory(s, "plomeria");
  }
}
