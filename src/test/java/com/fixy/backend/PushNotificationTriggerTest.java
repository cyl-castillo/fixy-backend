package com.fixy.backend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.PushNotificationService;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifica los puntos de disparo reales de Web Push (Ola UX), con
 * {@link PushNotificationService} mockeado (patrón
 * TelegramOpportunityTriggerTest: el contenido exacto del push y la
 * criptografía ya están cubiertos por PushNotificationServiceTest; esto
 * prueba que el hook se llama desde el lugar correcto con los datos
 * correctos):
 *
 *  (a) mensaje del agente/ops a un lead → notifyLeadHasNews;
 *  (b) mensaje del cliente en un lead YA ASIGNADO → notifyProvider;
 *  (c) mensaje del cliente en un lead SIN asignar → no llama a notifyProvider
 *      (no hay a quién avisar).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PushNotificationTriggerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ProviderRepository providerRepository;

  @MockitoBean
  private PushNotificationService pushNotificationService;

  private String accessTokenOf(Integer leadId, MvcResult createResult) throws Exception {
    return JsonPath.read(createResult.getResponse().getContentAsString(), "$.accessToken");
  }

  @Test
  void agentMessageToLead_triggersNotifyLeadHasNews() throws Exception {
    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099400001",
                  "problem": "Se me rompió la canilla",
                  "channel": "web-app",
                  "serviceCategory": "plomeria",
                  "zone": "Solymar",
                  "urgency": "media"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");
    String token = accessTokenOf(leadId, createResult);

    // Simulamos el aviso "fixy" (mismo sender que usa dispatchEscalation /
    // postFromAgent) vía el endpoint de ops, que llama a postFromOps.
    mockMvc.perform(post("/api/leads/{id}/messages", leadId)
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sender\": \"fixy\", \"text\": \"Confirmamos tu turno para mañana\"}"))
        .andExpect(status().isCreated());

    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> verify(pushNotificationService, times(1))
            .notifyLeadHasNews(eq(leadId.longValue()), anyString(), contains("Confirmamos tu turno")));
  }

  @Test
  void customerMessageOnAssignedLead_triggersNotifyProvider() throws Exception {
    Provider provider = new Provider();
    provider.setName("Juan Plomero");
    provider.setPhone("099888777");
    provider.setCategories("plomeria");
    provider.setPrimaryZone("Solymar");
    provider.setStatus(ProviderStatus.AVAILABLE);
    provider.setAccessToken("prov-tok-push-test");
    Provider savedProvider = providerRepository.save(provider);

    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099400002",
                  "problem": "Se me rompió la canilla",
                  "channel": "web-app",
                  "serviceCategory": "plomeria",
                  "zone": "Solymar",
                  "urgency": "media"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");
    String token = accessTokenOf(leadId, createResult);

    mockMvc.perform(post("/api/public/providers/{pid}/opportunities/{lid}/accept", savedProvider.getId(), leadId)
            .param("token", savedProvider.getAccessToken()))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"Puede ser mañana a las 10?\"}"))
        .andExpect(status().isCreated());

    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> verify(pushNotificationService, times(1))
            .notifyProvider(eq(savedProvider.getId()), eq("prov-tok-push-test"),
                anyString(), contains("Puede ser mañana")));
  }

  @Test
  void customerMessageOnUnassignedLead_doesNotTriggerNotifyProvider() throws Exception {
    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099400004",
                  "problem": "Se me rompió la canilla",
                  "channel": "web-app",
                  "serviceCategory": "plomeria",
                  "zone": "Solymar",
                  "urgency": "media"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer freshLeadId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");
    String token = JsonPath.read(createResult.getResponse().getContentAsString(), "$.accessToken");

    mockMvc.perform(post("/api/public/leads/{id}/messages", freshLeadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"hola, alguna novedad?\"}"))
        .andExpect(status().isCreated());

    Thread.sleep(500);
    verify(pushNotificationService, never()).notifyProvider(anyLong(), any(), anyString(), anyString());
  }
}
