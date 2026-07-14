package com.fixy.backend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.TelegramNotifyService;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifica que el gatillo real (LeadAgentService.tryAutoMatch, camino
 * chat-first) llama a TelegramNotifyService cuando un lead cruza a
 * readyForMatching con proveedores disponibles — sin duplicar el aviso en
 * turnos posteriores del mismo lead. TelegramNotifyService se mockea acá
 * (patrón WhatsAppWebhookCustomerFlowTest) porque el contenido exacto del
 * mensaje ya está cubierto por TelegramNotifyServiceTest; esto prueba el
 * punto de disparo end-to-end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
    "fixy.cloudflare.api-token="
})
class TelegramOpportunityTriggerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ProviderRepository providerRepository;

  @MockitoBean
  private TelegramNotifyService telegramNotifyService;

  private void createPlomeroInSolymar() {
    Provider provider = new Provider();
    provider.setName("Juan Plomero");
    provider.setPhone("099888777");
    provider.setCategories("plomeria");
    provider.setPrimaryZone("Solymar");
    provider.setStatus(ProviderStatus.AVAILABLE);
    providerRepository.save(provider);
  }

  @Test
  void leadReadyWithMatchingProvider_triggersTelegramOpportunityOnce() throws Exception {
    createPlomeroInSolymar();

    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099222333",
                  "problem": "Necesito coordinar algo",
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
            .content("{\"text\": \"se me rompio una canilla, estoy en Solymar\"}"))
        .andExpect(status().isCreated());

    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> verify(telegramNotifyService, times(1))
            .notifyOpportunityWithMatches(any(Lead.class), anyList()));

    // Segundo mensaje del mismo cliente en el mismo lead: no debe volver a
    // llamar al notify (el lead ya está ready y ya se avisó una vez).
    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"che, alguna novedad?\"}"))
        .andExpect(status().isCreated());

    Thread.sleep(1000);
    verify(telegramNotifyService, times(1)).notifyOpportunityWithMatches(any(Lead.class), anyList());
  }
}
