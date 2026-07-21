package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.ProviderRepository;
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
 * Vertical pastelería (provider #10 Melissa, ya anunciada públicamente): un
 * pedido de torta en el chat debe clasificar como "pasteleria", quedar
 * readyForMatching en zona MVP, y matchear a un proveedor real de esa
 * categoría — igual que cualquier otro rubro del MVP. Usa provider=workersai
 * sin credenciales (mismo patrón que LeadAgentFallbackTest) para forzar el
 * fallback heurístico determinista, sin depender de un LLM real ni de datos
 * de prod: el proveedor se crea acá mismo.
 *
 * Sin @Transactional a propósito: respondToCustomerAsync corre @Async en otro
 * hilo (mismo motivo que LeadAgentFallbackTest) y no vería una transacción de
 * test no comiteada — el provider creado en setup debe quedar committeado de
 * verdad antes del POST del mensaje.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
    "fixy.cloudflare.api-token="
})
class PasteleriaMatchingTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ProviderRepository providerRepository;

  private void createPasteleriaProviderInShangrila() {
    Provider provider = new Provider();
    provider.setName("Melissa Pastelería");
    provider.setPhone("099888777");
    provider.setCategories("pasteleria");
    provider.setPrimaryZone("Shangrilá");
    provider.setStatus(ProviderStatus.AVAILABLE);
    providerRepository.save(provider);
  }

  @Test
  void pedidoDeTortaClasificaZonaYMatcheaAProveedorDePasteleria() throws Exception {
    createPasteleriaProviderInShangrila();

    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099222333",
                  "problem": "Necesito coordinar algo para un evento",
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
            .content("{\"text\": \"quiero una torta de cumpleaños tematica, estoy en Shangrilá\"}"))
        .andExpect(status().isCreated());

    String fixyReply = awaitFixyReplyMentioning(leadId, token, "melissa pastelería");

    // El lead quedó clasificado, con zona, y readyForMatching (categoría MVP + zona MVP).
    mockMvc.perform(get("/api/public/leads/{id}", leadId).param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.detectedCategory").value("pasteleria"))
        .andExpect(jsonPath("$.location").value("Shangrilá"))
        .andExpect(jsonPath("$.readyForMatching").value(true));

    // tryAutoMatch encontró a Melissa (único proveedor de pasteleria en Shangrilá) y avisó.
    assertThat(fixyReply.toLowerCase()).contains("melissa pastelería".toLowerCase());
    assertThat(fixyReply.toLowerCase()).contains("pastelería");
  }

  /**
   * Polling sobre el endpoint público de mensajes hasta ver la respuesta de
   * fixy que contiene el fragmento esperado (case-insensitive, post-async).
   * Esperar "cualquier mensaje de fixy" era una carrera con el saludo async
   * de greet(): si el poll caía entre el saludo y el aviso de tryAutoMatch,
   * el test agarraba el saludo (mismo fix que MatchingTransparencyTest).
   */
  private String awaitFixyReplyMentioning(Integer leadId, String token, String expectedFragment) {
    java.util.concurrent.atomic.AtomicReference<String> reply = new java.util.concurrent.atomic.AtomicReference<>();
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          MvcResult result = mockMvc.perform(get("/api/public/leads/{id}/messages", leadId)
                  .param("token", token))
              .andExpect(status().isOk())
              .andReturn();
          String listBody = result.getResponse().getContentAsString();
          java.util.List<String> senders = JsonPath.read(listBody, "$[*].sender");
          java.util.List<String> texts = JsonPath.read(listBody, "$[*].text");
          java.util.List<String> fixyTexts = new java.util.ArrayList<>();
          String match = null;
          String needle = expectedFragment.toLowerCase();
          for (int i = 0; i < senders.size(); i++) {
            if ("fixy".equals(senders.get(i))) {
              fixyTexts.add(texts.get(i));
              if (texts.get(i).toLowerCase().contains(needle)) {
                match = texts.get(i);
              }
            }
          }
          assertThat(match)
              .as("mensaje de fixy mencionando '%s' (mensajes de fixy vistos: %s)",
                  expectedFragment, fixyTexts)
              .isNotNull();
          reply.set(match);
        });
    return reply.get();
  }
}
