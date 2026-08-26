package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.PushSubscription;
import com.fixy.backend.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta pública de suscripción push (Fase Push-2, enganche): {@code POST
 * /api/public/push-subscriptions}, sin token, upsert por {@code endpoint}.
 * Endpoints propios por test (misma H2 compartida entre contextos, lección
 * conocida del repo) para no chocar con otros tests que también crean
 * PushSubscription.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicPushSubscriptionEndpointTest {

  @Autowired private org.springframework.test.web.servlet.MockMvc mockMvc;
  @Autowired private PushSubscriptionRepository pushSubscriptionRepository;

  private String body(String endpoint, String zone, String savedOfferIdsJsonArray) {
    return """
        {"endpoint": "%s", "keys": {"p256dh": "dummy-p256dh", "auth": "dummy-auth"}, "zone": "%s", "savedOfferIds": %s}
        """.formatted(endpoint, zone, savedOfferIdsJsonArray);
  }

  @Test
  void endpointDuplicadoHistorico_sobreviveLaFilaConIdentidadYSeBorranLasDemas() throws Exception {
    // Hotfix 2026-08-25: prod arrastra filas repetidas del mismo endpoint
    // (era pre-upsert). El upsert debe quedarse con UNA (la que tiene
    // lead/provider) y borrar el resto, en vez de tirar NonUniqueResult.
    String endpoint = "https://push-test.example/duplicated-historic";
    PushSubscription conLead = new PushSubscription();
    conLead.setEndpoint(endpoint);
    conLead.setP256dh("old-1");
    conLead.setAuth("old-1");
    conLead.setLeadId(9876543L);
    pushSubscriptionRepository.save(conLead);
    for (int i = 0; i < 2; i++) {
      PushSubscription huerfana = new PushSubscription();
      huerfana.setEndpoint(endpoint);
      huerfana.setP256dh("old-orphan-" + i);
      huerfana.setAuth("old-orphan-" + i);
      pushSubscriptionRepository.save(huerfana);
    }

    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(endpoint, "Solymar", "[7]")))
        .andExpect(status().isOk());

    PushSubscription superviviente = pushSubscriptionRepository.findByEndpoint(endpoint).orElseThrow();
    assertThat(superviviente.getLeadId()).isEqualTo(9876543L);
    assertThat(superviviente.getZone()).isEqualTo("Solymar");
    assertThat(superviviente.getP256dh()).isEqualTo("dummy-p256dh");
    assertThat(superviviente.getSavedOfferIds()).isEqualTo("7");
  }

  @Test
  void visitanteNuevo_seDaDeAltaSinLeadNiProvider() throws Exception {
    String endpoint = "https://push-test.example/visitor-new";

    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(endpoint, "Solymar", "[]")))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.ok").value(true));

    PushSubscription sub = pushSubscriptionRepository.findByEndpoint(endpoint).orElseThrow();
    assertThat(sub.getLeadId()).isNull();
    assertThat(sub.getProviderId()).isNull();
    assertThat(sub.getZone()).isEqualTo("Solymar");
  }

  @Test
  void mismoEndpointDeUnLead_actualizaZonaSinPisarLeadId() throws Exception {
    String endpoint = "https://push-test.example/lead-endpoint";
    PushSubscription existing = new PushSubscription();
    existing.setLeadId(777001L);
    existing.setEndpoint(endpoint);
    existing.setP256dh("old-p256dh");
    existing.setAuth("old-auth");
    existing.setZone("Lagomar");
    pushSubscriptionRepository.save(existing);

    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(endpoint, "El Pinar", "[]")))
        .andExpect(status().isOk());

    PushSubscription reloaded = pushSubscriptionRepository.findByEndpoint(endpoint).orElseThrow();
    assertThat(reloaded.getId()).isEqualTo(existing.getId());
    assertThat(reloaded.getLeadId()).as("no debe pisar el leadId existente").isEqualTo(777001L);
    assertThat(reloaded.getZone()).isEqualTo("El Pinar");
    assertThat(reloaded.getP256dh()).isEqualTo("dummy-p256dh");
  }

  @Test
  void zonaNoReconocida_quedaEnNullSinRomperElAlta() throws Exception {
    String endpoint = "https://push-test.example/zona-no-reconocida";

    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(endpoint, "Pocitos", "[]")))
        .andExpect(status().isOk());

    PushSubscription sub = pushSubscriptionRepository.findByEndpoint(endpoint).orElseThrow();
    assertThat(sub.getZone()).isNull();
  }

  @Test
  void savedOfferIds_sePersistenComoCsv() throws Exception {
    String endpoint = "https://push-test.example/saved-offer-ids";

    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(endpoint, "Aeroparque", "[101, 102, 103]")))
        .andExpect(status().isOk());

    PushSubscription sub = pushSubscriptionRepository.findByEndpoint(endpoint).orElseThrow();
    assertThat(sub.getSavedOfferIds()).isEqualTo("101,102,103");
  }

  @Test
  void savedOfferIds_vacio_quedaEnNull() throws Exception {
    String endpoint = "https://push-test.example/saved-offer-ids-empty";

    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(endpoint, "Aeroparque", "[]")))
        .andExpect(status().isOk());

    PushSubscription sub = pushSubscriptionRepository.findByEndpoint(endpoint).orElseThrow();
    assertThat(sub.getSavedOfferIds()).isNull();
  }

  @Test
  void savedOfferIds_masDe50_rechazaConBadRequest() throws Exception {
    StringBuilder ids = new StringBuilder("[");
    for (int i = 0; i < 51; i++) {
      if (i > 0) ids.append(",");
      ids.append(1000 + i);
    }
    ids.append("]");

    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("https://push-test.example/saved-offer-ids-too-many", "Aeroparque", ids.toString())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void savedOfferIds_noNumerico_rechazaConBadRequest() throws Exception {
    String malformed = """
        {"endpoint": "https://push-test.example/saved-offer-ids-bad", "keys": {"p256dh": "dummy-p256dh", "auth": "dummy-auth"}, "zone": "Aeroparque", "savedOfferIds": ["abc"]}
        """;

    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(malformed))
        .andExpect(status().isBadRequest());
  }

  @Test
  void endpointFaltante_rechazaConBadRequest() throws Exception {
    String malformed = """
        {"keys": {"p256dh": "dummy-p256dh", "auth": "dummy-auth"}, "zone": "Aeroparque", "savedOfferIds": []}
        """;

    mockMvc.perform(post("/api/public/push-subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(malformed))
        .andExpect(status().isBadRequest());
  }
}
