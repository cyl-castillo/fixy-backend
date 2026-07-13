package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * BLOCKER #3 (market-readiness): el turno conversacional NO puede quedar
 * mudo/ciego cuando el LLM externo (Cloudflare Workers AI) falla o no está
 * configurado. Este test fuerza esa caída con provider=workersai y sin
 * credenciales CF — callWorkersAiJson devuelve null de inmediato (sin red,
 * sin timeout, ver LeadAgentService.callWorkersAiJson) — y verifica que el
 * fallback determinista procese igual el mensaje del cliente con la
 * heurística (AgentService.classify) en vez de responder un enlatado ciego.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
    "fixy.cloudflare.api-token="
})
class LeadAgentFallbackTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void shouldExtractCategoryAndZoneFromMessageWhenLlmIsDown() throws Exception {
    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099111222",
                  "problem": "Necesito ayuda con algo en mi casa, no se que es",
                  "channel": "web-app"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    String body = createResult.getResponse().getContentAsString();
    Integer leadId = JsonPath.read(body, "$.id");
    String token = JsonPath.read(body, "$.accessToken");

    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"se me rompió la canilla, estoy en Solymar\"}"))
        .andExpect(status().isCreated());

    String fixyReply = awaitFixyReply(leadId, token);

    // Reconoce ambos datos extraídos por la heurística — nunca un enlatado genérico.
    assertThat(fixyReply.toLowerCase()).contains("plomer");
    assertThat(fixyReply).contains("Solymar");
    assertThat(fixyReply.toLowerCase()).doesNotContain("contame un poco más");

    // El lead quedó actualizado con lo extraído por la heurística (mismo
    // contrato que el LLM hubiera producido).
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/public/leads/{id}", leadId)
            .param("token", token))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .jsonPath("$.detectedCategory").value("plomeria"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .jsonPath("$.location").value("Solymar"));
  }

  @Test
  void shouldAskHonestlyWhenMessageIsAmbiguousAndLlmIsDown() throws Exception {
    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099333444",
                  "problem": "Necesito ayuda con algo en mi casa, no se que es",
                  "channel": "web-app"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    String body = createResult.getResponse().getContentAsString();
    Integer leadId = JsonPath.read(body, "$.id");
    String token = JsonPath.read(body, "$.accessToken");

    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"??\"}"))
        .andExpect(status().isCreated());

    String fixyReply = awaitFixyReply(leadId, token);

    // Mensaje sin info real: repregunta honesta está bien acá.
    assertThat(fixyReply.toLowerCase()).contains("contame un poco más");
  }

  @Test
  void shouldOfferMatchingHonestlyWhenNoProviderAvailable() throws Exception {
    // Sin proveedores de barometrica en Lagomar dados de alta: el fallback
    // NUNCA debe prometer contacto inmediato inexistente.
    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099555666",
                  "problem": "Necesito ayuda con algo en mi casa, no se que es",
                  "channel": "web-app"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    String body = createResult.getResponse().getContentAsString();
    Integer leadId = JsonPath.read(body, "$.id");
    String token = JsonPath.read(body, "$.accessToken");

    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"se me tapo la camara septica, estoy en Lagomar\"}"))
        .andExpect(status().isCreated());

    String fixyReply = awaitFixyReply(leadId, token);

    // El lead cruzó a readyForMatching (categoría+zona MVP) y tryAutoMatch ya
    // avisó honestamente que no hay proveedores — sin prometer contacto falso.
    assertThat(fixyReply.toLowerCase()).contains("no tengo proveedores libres");
    assertThat(fixyReply.toLowerCase()).contains("lagomar");
    assertThat(fixyReply.toLowerCase()).contains("barométrica");
    // Nunca prometer contacto/asignación inmediata sin proveedor real.
    assertThat(fixyReply.toLowerCase()).doesNotContain("conseguí a");
  }

  /** Polling corto sobre el endpoint público de mensajes hasta ver la respuesta de fixy (post-async). */
  private String awaitFixyReply(Integer leadId, String token) {
    java.util.concurrent.atomic.AtomicReference<String> reply = new java.util.concurrent.atomic.AtomicReference<>();
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                  .get("/api/public/leads/{id}/messages", leadId)
                  .param("token", token))
              .andExpect(status().isOk())
              .andReturn();
          String listBody = result.getResponse().getContentAsString();
          java.util.List<String> senders = JsonPath.read(listBody, "$[*].sender");
          assertThat(senders).contains("fixy");
          int idx = senders.lastIndexOf("fixy");
          reply.set(JsonPath.read(listBody, "$[" + idx + "].text"));
        });
    return reply.get();
  }
}
