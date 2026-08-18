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
  @Autowired private OfferExpirationScheduler offerExpirationScheduler;

  private String item(String externalKey, String sourceName, String businessName, String title, boolean allZones) {
    return item(externalKey, sourceName, businessName, title, allZones, "2026-12-01T00:00:00Z");
  }

  private String item(String externalKey, String sourceName, String businessName, String title,
      boolean allZones, String validUntil) {
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
          "validUntil": "%s"
        }
        """.formatted(externalKey, sourceName, businessName, title, allZones, validUntil);
  }

  /** Aprueba la oferta de esa externalKey que está en la cola y devuelve su id. */
  private int approveByExternalKey(String key) throws Exception {
    MvcResult queue = mockMvc.perform(get("/api/offers").param("status", "draft")
            .with(httpBasic("test-ops", "test-pass")))
        .andReturn();
    List<Object> matches = JsonPath.read(queue.getResponse().getContentAsString(),
        "$[?(@.externalKey == '" + key + "')]");
    assertThat(matches).hasSize(1);
    int offerId = (Integer) ((Map<?, ?>) matches.get(0)).get("id");
    mockMvc.perform(post("/api/offers/{id}/approve", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());
    return offerId;
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

  @Test
  void ofertaAprobadaQueSigueEnLaFuenteRenuevaSuVigenciaSinPisarElContenido() throws Exception {
    String key = "test-ing-" + System.nanoTime();
    ingest(item(key, "Fuente Test G", "Comercio Renueva Test", "20% off local", false,
        "2026-09-01T00:00:00Z"));
    int offerId = approveByExternalKey(key);

    // Corrida siguiente: el banco sigue publicando el beneficio, con vigencia más lejana.
    MvcResult res = ingest(item(key, "Fuente Test G", "Comercio Renueva Test",
        "TITULO QUE NO DEBE APLICARSE", false, "2026-09-08T00:00:00Z"));
    Map<String, Object> body = JsonPath.read(res.getResponse().getContentAsString(), "$");
    assertThat(((Number) body.get("revalidated")).intValue()).isEqualTo(1);
    assertThat(((Number) body.get("refreshed")).intValue()).isEqualTo(0);
    assertThat(((Number) body.get("created")).intValue()).isEqualTo(0);

    // La vigencia se movió; el contenido y el estado aprobado quedaron intactos.
    mockMvc.perform(get("/api/offers/{id}", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.title").value("20% off local"))
        .andExpect(jsonPath("$.validUntil").value(org.hamcrest.Matchers.startsWith("2026-09-08")));
  }

  @Test
  void laVigenciaDeUnaOfertaAprobadaNuncaSeRecorta() throws Exception {
    String key = "test-ing-" + System.nanoTime();
    ingest(item(key, "Fuente Test H", "Comercio No Recorta Test", "20% off local", false,
        "2026-11-01T00:00:00Z"));
    int offerId = approveByExternalKey(key);

    // La fuente manda una fecha ANTERIOR a la ya aprobada: no se toca nada.
    MvcResult res = ingest(item(key, "Fuente Test H", "Comercio No Recorta Test", "20% off local", false,
        "2026-10-01T00:00:00Z"));
    Map<String, Object> body = JsonPath.read(res.getResponse().getContentAsString(), "$");
    assertThat(((Number) body.get("revalidated")).intValue()).isEqualTo(0);

    mockMvc.perform(get("/api/offers/{id}", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(jsonPath("$.validUntil").value(org.hamcrest.Matchers.startsWith("2026-11-01")));
  }

  @Test
  void ofertaVencidaQueLaFuenteSiguePublicandoVuelveALaColaDeAprobacion() throws Exception {
    String key = "test-ing-" + System.nanoTime();
    // Nace, se aprueba y vence (validUntil en el pasado + el scheduler de expiración).
    ingest(item(key, "Fuente Test I", "Comercio Revive Test", "20% off local", false,
        "2020-01-01T00:00:00Z"));
    int offerId = approveByExternalKey(key);
    offerExpirationScheduler.processOnce();
    mockMvc.perform(get("/api/offers/{id}", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(jsonPath("$.status").value("EXPIRED"));

    // El banco la sigue listando: vuelve a DRAFT con datos frescos, NO a ACTIVE sola.
    MvcResult res = ingest(item(key, "Fuente Test I", "Comercio Revive Test", "25% off (actualizado)", false,
        "2026-12-31T00:00:00Z"));
    Map<String, Object> body = JsonPath.read(res.getResponse().getContentAsString(), "$");
    assertThat(((Number) body.get("reopened")).intValue()).isEqualTo(1);
    assertThat(((Number) body.get("created")).intValue()).isEqualTo(0);

    mockMvc.perform(get("/api/offers/{id}", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.title").value("25% off (actualizado)"));
  }

  @Test
  void ofertaRechazadaAManoNoResucitaAunqueLaFuenteLaSigaPublicando() throws Exception {
    String source = "Fuente Test J " + System.nanoTime();
    String key = "keyj-" + System.nanoTime();
    String otra = "keyj2-" + System.nanoTime();
    ingest(item(key, source, "Comercio Rechazado Test", "20% off local", false),
        item(otra, source, "Comercio J2", "Oferta 2", false));

    // key desaparece de la fuente → queda REJECTED por la limpieza de cola.
    ingest(item(otra, source, "Comercio J2", "Oferta 2", false));

    // Vuelve a aparecer en la fuente: sigue REJECTED, la ingesta no revierte un rechazo.
    MvcResult res = ingest(item(key, source, "Comercio Rechazado Test", "20% off local", false),
        item(otra, source, "Comercio J2", "Oferta 2", false));
    Map<String, Object> body = JsonPath.read(res.getResponse().getContentAsString(), "$");
    assertThat(((Number) body.get("reopened")).intValue()).isEqualTo(0);
    assertThat(((Number) body.get("created")).intValue()).isEqualTo(0);

    MvcResult all = mockMvc.perform(get("/api/offers").with(httpBasic("test-ops", "test-pass")))
        .andReturn();
    List<Object> matches = JsonPath.read(all.getResponse().getContentAsString(),
        "$[?(@.externalKey == '" + key + "')]");
    assertThat(matches).hasSize(1);
    assertThat(((Map<?, ?>) matches.get(0)).get("status")).isEqualTo("REJECTED");
  }
}
