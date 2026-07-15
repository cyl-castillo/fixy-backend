package com.fixy.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.fixy.backend.repository.PushSubscriptionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Endpoints públicos de Web Push (Ola UX): alta de suscripción de
 * cliente/proveedor con el mismo auth por token que el resto de
 * /api/public, y el GET de la clave pública VAPID que el frontend necesita
 * para {@code PushManager.subscribe()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "fixy.push.vapid-public-key=BIgTEzJ-hmuHgo5VSU9gDge3EfvM9zfNBd81XdwQZ2mJJONEpWsRdPfMVFlkjIu7NkkyU9kgHAoMFPDPby4JiMQ",
    "fixy.push.vapid-private-key=Qf4h3DlXM2gjkIVCcIhaY5n8I7AkX9S9mnjNOlb_fGs"
})
class PushSubscriptionEndpointTest {

  private static final String SUB_BODY = """
      {"endpoint": "%s", "keys": {"p256dh": "fake-p256dh-value", "auth": "fake-auth-value"}}
      """;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private PushSubscriptionRepository pushSubscriptionRepository;

  private Integer createReadyLead(String phone) throws Exception {
    String payload = """
        {
          "name": "Cliente Test",
          "phone": "%s",
          "problem": "Se me rompió la canilla",
          "channel": "web-app",
          "serviceCategory": "plomeria",
          "zone": "Solymar",
          "urgency": "media"
        }
        """.formatted(phone);
    MvcResult result = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private Integer createProvider(String name, String phone) throws Exception {
    String payload = """
        {
          "name": "%s",
          "phone": "%s",
          "primaryZone": "Solymar",
          "city": "Ciudad de la Costa",
          "categories": "plomeria"
        }
        """.formatted(name, phone);
    MvcResult result = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private String providerAccessToken(Integer providerId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }

  @Test
  void vapidPublicKey_returnsConfiguredKey() throws Exception {
    mockMvc.perform(get("/api/public/push/vapid-public-key"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publicKey")
            .value("BIgTEzJ-hmuHgo5VSU9gDge3EfvM9zfNBd81XdwQZ2mJJONEpWsRdPfMVFlkjIu7NkkyU9kgHAoMFPDPby4JiMQ"));
  }

  @Test
  void subscribeLead_withValidToken_persists() throws Exception {
    String payload = """
        {
          "name": "Cliente Push",
          "phone": "099300000",
          "problem": "Se me rompió la canilla",
          "channel": "web-app",
          "serviceCategory": "plomeria",
          "zone": "Solymar",
          "urgency": "media"
        }
        """;
    MvcResult created = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    String token = JsonPath.read(created.getResponse().getContentAsString(), "$.accessToken");

    mockMvc.perform(post("/api/public/leads/{id}/push-subscription", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(SUB_BODY.formatted("https://fcm.googleapis.com/fcm/send/lead-endpoint")))
        .andExpect(status().isNoContent());

    List<com.fixy.backend.model.PushSubscription> subs =
        pushSubscriptionRepository.findByLeadId(leadId.longValue());
    org.assertj.core.api.Assertions.assertThat(subs).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(subs.get(0).getEndpoint())
        .isEqualTo("https://fcm.googleapis.com/fcm/send/lead-endpoint");
  }

  @Test
  void subscribeLead_withInvalidToken_rejectsAsForbidden() throws Exception {
    Integer leadId = createReadyLead("099300001");

    mockMvc.perform(post("/api/public/leads/{id}/push-subscription", leadId)
            .param("token", "wrong-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(SUB_BODY.formatted("https://fcm.googleapis.com/fcm/send/other")))
        .andExpect(status().isForbidden());
  }

  @Test
  void subscribeProvider_withValidToken_persists() throws Exception {
    Integer providerId = createProvider("Melissa Push", "099300002");
    String token = providerAccessToken(providerId);

    mockMvc.perform(post("/api/public/providers/{id}/push-subscription", providerId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(SUB_BODY.formatted("https://fcm.googleapis.com/fcm/send/provider-endpoint")))
        .andExpect(status().isNoContent());

    List<com.fixy.backend.model.PushSubscription> subs =
        pushSubscriptionRepository.findByProviderId(providerId.longValue());
    org.assertj.core.api.Assertions.assertThat(subs).hasSize(1);
  }

  @Test
  void subscribeProvider_withInvalidToken_rejectsAsForbidden() throws Exception {
    Integer providerId = createProvider("Melissa Push Rechazo", "099300003");

    mockMvc.perform(post("/api/public/providers/{id}/push-subscription", providerId)
            .param("token", "wrong-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(SUB_BODY.formatted("https://fcm.googleapis.com/fcm/send/rejected")))
        .andExpect(status().isForbidden());
  }
}
