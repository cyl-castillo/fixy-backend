package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.PushSubscription;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code merchantToken} opcional del alta pública de suscripción push (Fase
 * 5, panel self-service del comercio): {@code POST
 * /api/public/push-subscriptions} liga la fila a un {@code Business} cuando
 * el token resuelve, la ignora en silencio si no.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicPushSubscriptionMerchantTokenTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private BusinessRepository businessRepository;
  @Autowired private PushSubscriptionRepository pushSubscriptionRepository;

  private Business persistBusinessWithToken(String tag) {
    Business business = new Business();
    business.setName("Comercio Merchant Token Test " + tag);
    business.setWhatsappNumber("0987" + tag);
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    business.setPanelToken("merchant-token-test-" + tag);
    return businessRepository.save(business);
  }

  private String body(String endpoint, String merchantTokenJsonField) {
    return """
        {"endpoint": "%s", "keys": {"p256dh": "dummy-p256dh", "auth": "dummy-auth"}%s}
        """.formatted(endpoint, merchantTokenJsonField);
  }

  @Test
  void merchantTokenValido_seteaBusinessId() throws Exception {
    Business business = persistBusinessWithToken("001");
    String endpoint = "https://push-test.example/merchant-token-valido";

    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(endpoint, ", \"merchantToken\": \"%s\"".formatted(business.getPanelToken()))))
        .andExpect(status().isOk());

    PushSubscription sub = pushSubscriptionRepository.findByEndpoint(endpoint).orElseThrow();
    assertThat(sub.getBusinessId()).isEqualTo(business.getId());
    assertThat(sub.getLeadId()).isNull();
    assertThat(sub.getProviderId()).isNull();
  }

  @Test
  void merchantTokenInvalido_seIgnoraSinRomperElAlta() throws Exception {
    String endpoint = "https://push-test.example/merchant-token-invalido";

    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(endpoint, ", \"merchantToken\": \"token-que-no-existe\"")))
        .andExpect(status().isOk());

    PushSubscription sub = pushSubscriptionRepository.findByEndpoint(endpoint).orElseThrow();
    assertThat(sub.getBusinessId()).isNull();
  }

  @Test
  void sinMerchantToken_businessIdQuedaNull() throws Exception {
    String endpoint = "https://push-test.example/sin-merchant-token";

    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(endpoint, "")))
        .andExpect(status().isOk());

    PushSubscription sub = pushSubscriptionRepository.findByEndpoint(endpoint).orElseThrow();
    assertThat(sub.getBusinessId()).isNull();
  }

  @Test
  void merchantTokenInvalidoEnUpdate_noPisaBusinessIdYaSeteado() throws Exception {
    Business business = persistBusinessWithToken("002");
    String endpoint = "https://push-test.example/merchant-token-update";

    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(endpoint, ", \"merchantToken\": \"%s\"".formatted(business.getPanelToken()))))
        .andExpect(status().isOk());

    // Segundo upsert del mismo endpoint, ahora con un token que no resuelve:
    // el businessId ya seteado sigue intacto (nunca se pisa a null).
    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(endpoint, ", \"merchantToken\": \"token-inexistente\"")))
        .andExpect(status().isOk());

    PushSubscription sub = pushSubscriptionRepository.findByEndpoint(endpoint).orElseThrow();
    assertThat(sub.getBusinessId()).isEqualTo(business.getId());
  }
}
