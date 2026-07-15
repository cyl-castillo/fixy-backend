package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Cotización Estimada (Ola 2 MVP), fallback heurístico sin LLM: mismo patrón
 * que LeadAgentFallbackTest (provider=workersai sin credenciales fuerza la
 * caída del LLM y ejercita respondWithHeuristicFallback). Verifica que una
 * pregunta de precio ("cuánto sale/cuesta/vale") se responda con el rango
 * orientativo + disclaimer cuando hay categoría, y pida el dato si no la hay.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
    "fixy.cloudflare.api-token="
})
class LeadAgentPriceFallbackTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void fallbackAnswersPriceRangeWithDisclaimerWhenCategoryKnown() throws Exception {
    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099777888",
                  "problem": "se me rompio la canilla, estoy en Solymar",
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
            .content("{\"text\": \"y cuanto sale mas o menos?\"}"))
        .andExpect(status().isCreated());

    String fixyReply = awaitFixyReply(leadId, token);

    assertThat(fixyReply).contains("$800–2500");
    assertThat(fixyReply.toLowerCase()).contains("proveedor");
    assertThat(fixyReply.toLowerCase()).contains("confirma");
  }

  @Test
  void fallbackAsksForCategoryWhenPriceQuestionArrivesWithoutCategory() throws Exception {
    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099777999",
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
            .content("{\"text\": \"cuanto cuesta?\"}"))
        .andExpect(status().isCreated());

    String fixyReply = awaitFixyReply(leadId, token);

    assertThat(fixyReply.toLowerCase()).contains("necesitás arreglar");
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
