package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.model.ProviderVerificationStatus;
import com.fixy.backend.model.PushSubscription;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.repository.PushSubscriptionRepository;
import com.fixy.backend.service.PushNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * POST /api/providers/{id}/notify (ops): push suelto a un proveedor. Con
 * suscripciones manda y dice sent=true; sin suscripciones NO llama al push y
 * dice sent=false con subscriptions=0 (ops va por WhatsApp). Sin auth, 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderNotifyEndpointTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
  @MockitoBean private PushNotificationService pushNotificationService;

  private Provider createProvider(String name, String token) {
    Provider p = new Provider();
    p.setName(name);
    p.setPhone("099" + Math.abs(name.hashCode() % 1000000));
    p.setCategories("aires_acondicionados");
    p.setPrimaryZone("Lagomar");
    p.setSourceType("manual");
    p.setStatus(ProviderStatus.AVAILABLE);
    p.setVerificationStatus(ProviderVerificationStatus.VERIFIED);
    p.setAccessToken(token);
    return providerRepository.save(p);
  }

  @Test
  void mandaElPushConElTokenExistenteYDevuelveSent() throws Exception {
    Provider provider = createProvider("Notify Con Sub", "notify-tok-1");
    PushSubscription sub = new PushSubscription();
    sub.setProviderId(provider.getId());
    sub.setEndpoint("https://push.example/notify-con-sub");
    sub.setP256dh("k");
    sub.setAuth("a");
    pushSubscriptionRepository.save(sub);

    mockMvc.perform(post("/api/providers/{id}/notify", provider.getId())
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Novedad en tu panel\",\"body\":\"Ya podés cargar horario y foto.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sent").value(true))
        .andExpect(jsonPath("$.subscriptions").value(1));

    verify(pushNotificationService, times(1))
        .notifyProvider(eq(provider.getId()), eq("notify-tok-1"), eq("Novedad en tu panel"), eq("Ya podés cargar horario y foto."));
    assertThat(providerRepository.findById(provider.getId()).orElseThrow().getAccessToken()).isEqualTo("notify-tok-1");
  }

  @Test
  void sinSuscripcionesNoMandaYLoDice() throws Exception {
    Provider provider = createProvider("Notify Sin Sub", "notify-tok-2");

    mockMvc.perform(post("/api/providers/{id}/notify", provider.getId())
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Hola\",\"body\":\"Probando\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sent").value(false))
        .andExpect(jsonPath("$.subscriptions").value(0));

    verify(pushNotificationService, never()).notifyProvider(eq(provider.getId()), anyString(), anyString(), anyString());
  }

  @Test
  void validaBodyYAuth() throws Exception {
    Provider provider = createProvider("Notify Valida", "notify-tok-3");

    mockMvc.perform(post("/api/providers/{id}/notify", provider.getId())
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"\",\"body\":\"x\"}"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(post("/api/providers/{id}/notify", provider.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Hola\",\"body\":\"x\"}"))
        .andExpect(status().isUnauthorized());

    verify(pushNotificationService, never()).notifyProvider(anyLong(), anyString(), anyString(), anyString());
  }
}
