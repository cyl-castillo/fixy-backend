package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
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
 * Ola 1 #2 de PLAN_SUPERAPP_CLIENTE.md: LeadAgentService.tryAutoMatch le decía
 * al cliente "Conseguí a X" ANTES de que el proveedor confirmara — si el
 * proveedor rechazaba después, se rompía la confianza. Este test verifica que
 * el copy es honesto ("estoy contactando", no "conseguí") y que el evento de
 * timeline PROVIDER_CONTACTED queda registrado con el mismo tono, ANTES de
 * cualquier confirmación real del proveedor.
 *
 * Mismo patrón que PasteleriaMatchingTest: provider=workersai sin credenciales
 * fuerza el fallback heurístico determinista (sin red, sin LLM real), que
 * también pasa por tryAutoMatch cuando el lead cruza a readyForMatching.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
    "fixy.cloudflare.api-token="
})
class MatchingTransparencyTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ProviderRepository providerRepository;

  @Autowired
  private LeadEventRepository leadEventRepository;

  // Zona+categoría poco usada por otros tests de la suite (jardineria en
  // Aeroparque) para minimizar el riesgo de matchear con un proveedor sembrado
  // por otro test que corre en el mismo contexto Spring/H2 compartido.
  private Provider createGardenerInAeroparque() {
    Provider provider = new Provider();
    provider.setName("Jardinero Aeroparque Test");
    provider.setPhone("099444555");
    provider.setCategories("jardineria");
    provider.setPrimaryZone("Aeroparque");
    provider.setStatus(ProviderStatus.AVAILABLE);
    return providerRepository.save(provider);
  }

  @Test
  void tryAutoMatchAvisaQueEstaContactandoNoQueYaConsiguioAlProveedor() throws Exception {
    createGardenerInAeroparque();

    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099666777",
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
            .content("{\"text\": \"necesito cortar el pasto del jardín, estoy en Aeroparque\"}"))
        .andExpect(status().isCreated());

    String fixyReply = awaitFixyReply(leadId, token);

    // Copy honesto: "contactando"/"escribiendo", nunca dar por hecho que el
    // proveedor ya aceptó antes de que confirme.
    assertThat(fixyReply).contains("Jardinero Aeroparque Test");
    assertThat(fixyReply.toLowerCase()).contains("contactando");
    assertThat(fixyReply.toLowerCase()).doesNotContain("conseguí");

    // El evento de timeline también es honesto: registra que se está
    // contactando al proveedor, no que ya está asignado/confirmado.
    List<LeadEvent> events = leadEventRepository.findByLeadIdOrderByCreatedAtAsc(leadId.longValue());
    assertThat(events).extracting(LeadEvent::getType).contains("PROVIDER_CONTACTED");
    LeadEvent contactedEvent = events.stream()
        .filter(e -> "PROVIDER_CONTACTED".equals(e.getType()))
        .findFirst()
        .orElseThrow();
    assertThat(contactedEvent.getMessage().toLowerCase()).contains("contactando");
    assertThat(contactedEvent.getMessage().toLowerCase()).doesNotContain("conseguí");
  }

  /** Polling corto sobre el endpoint público de mensajes hasta ver la respuesta de fixy (post-async). */
  private String awaitFixyReply(Integer leadId, String token) {
    java.util.concurrent.atomic.AtomicReference<String> reply = new java.util.concurrent.atomic.AtomicReference<>();
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          MvcResult result = mockMvc.perform(get("/api/public/leads/{id}/messages", leadId)
                  .param("token", token))
              .andExpect(status().isOk())
              .andReturn();
          String listBody = result.getResponse().getContentAsString();
          List<String> senders = JsonPath.read(listBody, "$[*].sender");
          assertThat(senders).contains("fixy");
          int idx = senders.lastIndexOf("fixy");
          reply.set(JsonPath.read(listBody, "$[" + idx + "].text"));
        });
    return reply.get();
  }
}
