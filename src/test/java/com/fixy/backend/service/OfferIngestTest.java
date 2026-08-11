package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code POST /api/offers/ingest} — ingesta idempotente de la corrida diaria
 * de scraping (maquina/scripts/ofertas-fuentes/). Publicación SIEMPRE
 * mediada por aprobación humana: nada de este endpoint pasa a ACTIVE.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OfferIngestTest {

  @Autowired private MockMvc mockMvc;

  private String item(String externalKey, String sourceName, String businessName, String title, boolean allZones) {
    return """
        {
          "externalKey": "%s",
          "sourceName": "%s",
          "sourceUrl": "https://fuente.example/beneficios",
          "businessName": "%s",
          "businessCategory": "otro",
          "title": "%s",
          "category": "otro",
          "zone": "Solymar",
          "allZones": %s,
          "discountText": "20%% off",
          "validUntil": "2026-12-01T00:00:00Z"
        }
        """.formatted(externalKey, sourceName, businessName, title, allZones);
  }

  private MvcResult ingest(String... items) throws Exception {
    String body = "{\"offers\": [" + String.join(",", items) + "]}";
    return mockMvc.perform(post("/api/offers/ingest")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andReturn();
  }

  @Test
  void primeraCorridaCreaOfertasEnDraftYComercioNuevo() throws Exception {
    String key = "test-ing-" + System.nanoTime();
    MvcResult res = ingest(item(key, "Fuente Test A", "Comercio Nuevo Test", "20% off local", false));

    Map<String, Object> body = JsonPath.read(res.getResponse().getContentAsString(), "$");
    assertThat(((Number) body.get("created")).intValue()).isEqualTo(1);
    assertThat(((Number) body.get("refreshed")).intValue()).isEqualTo(0);
    assertThat(((Number) body.get("discarded")).intValue()).isEqualTo(0);

    // La oferta creada está en la cola de aprobación (DRAFT), nunca ACTIVE sola.
    MvcResult queue = mockMvc.perform(get("/api/offers").param("status", "draft")
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String queueBody = queue.getResponse().getContentAsString();
    List<Object> matches = JsonPath.read(queueBody, "$[?(@.externalKey == '" + key + "')]");
    assertThat(matches).hasSize(1);
    Map<?, ?> offer = (Map<?, ?>) matches.get(0);
    assertThat(offer.get("status")).isEqualTo("DRAFT");
    assertThat(offer.get("origin")).isEqualTo("scraped_source");
    assertThat(offer.get("sourceName")).isEqualTo("Fuente Test A");
  }

  @Test
  void segundaCorridaConMismaExternalKeyRefrescaSiSigueEnDraft() throws Exception {
    String key = "test-ing-" + System.nanoTime();
    ingest(item(key, "Fuente Test B", "Comercio Refresh Test", "20% off local", false));

    MvcResult res2 = ingest(item(key, "Fuente Test B", "Comercio Refresh Test", "30% off local (actualizado)", false));
    Map<String, Object> body = JsonPath.read(res2.getResponse().getContentAsString(), "$");
    assertThat(((Number) body.get("created")).intValue()).isEqualTo(0);
    assertThat(((Number) body.get("refreshed")).intValue()).isEqualTo(1);

    MvcResult queue = mockMvc.perform(get("/api/offers").param("status", "draft")
            .with(httpBasic("test-ops", "test-pass")))
        .andReturn();
    List<Object> matches = JsonPath.read(queue.getResponse().getContentAsString(),
        "$[?(@.externalKey == '" + key + "')]");
    assertThat(matches).hasSize(1);
    assertThat(((Map<?, ?>) matches.get(0)).get("title")).isEqualTo("30% off local (actualizado)");
  }

  @Test
  void unaOfertaYaAprobadaNoSePisaSolaAunqueLaFuenteMandeDatosNuevos() throws Exception {
    String key = "test-ing-" + System.nanoTime();
    ingest(item(key, "Fuente Test C", "Comercio Aprobado Test", "20% off local", false));

    MvcResult queue = mockMvc.perform(get("/api/offers").param("status", "draft")
            .with(httpBasic("test-ops", "test-pass")))
        .andReturn();
    List<Object> matches = JsonPath.read(queue.getResponse().getContentAsString(),
        "$[?(@.externalKey == '" + key + "')]");
    Integer offerId = (Integer) ((Map<?, ?>) matches.get(0)).get("id");

    mockMvc.perform(post("/api/offers/{id}/approve", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());

    MvcResult res2 = ingest(item(key, "Fuente Test C", "Comercio Aprobado Test", "TITULO QUE NO DEBE APLICARSE", false));
    Map<String, Object> body = JsonPath.read(res2.getResponse().getContentAsString(), "$");
    assertThat(((Number) body.get("created")).intValue()).isEqualTo(0);
    assertThat(((Number) body.get("refreshed")).intValue()).isEqualTo(0);

    mockMvc.perform(get("/api/offers/{id}", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.title").value("20% off local"));
  }

  @Test
  void ofertaDraftQueYaNoVieneDeLaFuenteSeMarcaRejected() throws Exception {
    String source = "Fuente Test D " + System.nanoTime();
    String key1 = "key1-" + System.nanoTime();
    String key2 = "key2-" + System.nanoTime();
    ingest(
        item(key1, source, "Comercio D1", "Oferta 1", false),
        item(key2, source, "Comercio D2", "Oferta 2", false)
    );

    // Segunda corrida: key2 ya no vino de la fuente.
    MvcResult res2 = ingest(item(key1, source, "Comercio D1", "Oferta 1", false));
    Map<String, Object> body = JsonPath.read(res2.getResponse().getContentAsString(), "$");
    assertThat(((Number) body.get("discarded")).intValue()).isEqualTo(1);

    MvcResult queue = mockMvc.perform(get("/api/offers")
            .with(httpBasic("test-ops", "test-pass")))
        .andReturn();
    List<Object> matches = JsonPath.read(queue.getResponse().getContentAsString(),
        "$[?(@.externalKey == '" + key2 + "')]");
    assertThat(matches).hasSize(1);
    assertThat(((Map<?, ?>) matches.get(0)).get("status")).isEqualTo("REJECTED");
  }

  @Test
  void ofertaActiveQueYaNoVieneDeLaFuenteNoSeTocaYSeReporta() throws Exception {
    String source = "Fuente Test E " + System.nanoTime();
    String key1 = "key1-" + System.nanoTime();
    String key2 = "key2-" + System.nanoTime();
    ingest(
        item(key1, source, "Comercio E1", "Oferta 1", false),
        item(key2, source, "Comercio E2", "Oferta 2", false)
    );

    MvcResult queue = mockMvc.perform(get("/api/offers").param("status", "draft")
            .with(httpBasic("test-ops", "test-pass")))
        .andReturn();
    List<Object> matches = JsonPath.read(queue.getResponse().getContentAsString(),
        "$[?(@.externalKey == '" + key2 + "')]");
    Integer offerId = (Integer) ((Map<?, ?>) matches.get(0)).get("id");
    mockMvc.perform(post("/api/offers/{id}/approve", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());

    // Segunda corrida: key2 (ya ACTIVE) desaparece de la fuente.
    MvcResult res2 = ingest(item(key1, source, "Comercio E1", "Oferta 1", false));
    Map<String, Object> body = JsonPath.read(res2.getResponse().getContentAsString(), "$");
    assertThat(((Number) body.get("discarded")).intValue()).isEqualTo(0);
    List<Integer> stillActive = JsonPath.read(res2.getResponse().getContentAsString(), "$.stillActiveMissingFromSource");
    assertThat(stillActive).contains(offerId);

    mockMvc.perform(get("/api/offers/{id}", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void listaVaciaEsRechazada() throws Exception {
    mockMvc.perform(post("/api/offers/ingest")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"offers\": []}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void requiereAutenticacionOps() throws Exception {
    mockMvc.perform(post("/api/offers/ingest")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"offers\": []}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void reusaElComercioExistenteEnCorridasSucesivasSinDuplicarlo() throws Exception {
    String business = "Comercio Reuso Test " + System.nanoTime();
    String source = "Fuente Test F " + System.nanoTime();
    ingest(item("kf1-" + System.nanoTime(), source, business, "Oferta 1", false));
    MvcResult res2 = ingest(item("kf2-" + System.nanoTime(), source, business, "Oferta 2", false));
    Map<String, Object> body = JsonPath.read(res2.getResponse().getContentAsString(), "$");
    assertThat(((Number) body.get("created")).intValue()).isEqualTo(1);

    MvcResult businesses = mockMvc.perform(get("/api/businesses")
            .with(httpBasic("test-ops", "test-pass")))
        .andReturn();
    List<Object> matches = JsonPath.read(businesses.getResponse().getContentAsString(),
        "$[?(@.name == '" + business + "')]");
    assertThat(matches).hasSize(1);
  }
}
