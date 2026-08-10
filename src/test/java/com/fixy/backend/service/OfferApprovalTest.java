package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Cola de aprobación de {@code Offer} (diseño §4.3 y §5): toda oferta nace
 * DRAFT, ninguna pasa a ACTIVE sin acción explícita de ops, y una oferta
 * aprobada sin validUntil se completa con la regla de vigencia default de
 * 14 días — nunca puede quedar ACTIVE sin fecha de vencimiento.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OfferApprovalTest {

  @Autowired private MockMvc mockMvc;

  private Integer createBusiness(String whatsapp) throws Exception {
    MvcResult res = mockMvc.perform(post("/api/businesses")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "Comercio Test Ofertas", "whatsappNumber": "%s", "category": "otro"}
                """.formatted(whatsapp)))
        .andExpect(status().isCreated())
        .andReturn();
    return JsonPath.read(res.getResponse().getContentAsString(), "$.id");
  }

  private Integer createOffer(Integer businessId, String extraJson) throws Exception {
    MvcResult res = mockMvc.perform(post("/api/offers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "businessId": %d,
                  "title": "20%% off en el local",
                  "category": "otro",
                  "zone": "Solymar"
                  %s
                }
                """.formatted(businessId, extraJson)))
        .andExpect(status().isCreated())
        .andReturn();
    return JsonPath.read(res.getResponse().getContentAsString(), "$.id");
  }

  @Test
  void naceEnDraftIndependientementeDelOrigen() throws Exception {
    Integer businessId = createBusiness("098222001");

    MvcResult res = mockMvc.perform(post("/api/offers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "businessId": %d,
                  "title": "Promo pan dulce",
                  "category": "otro",
                  "origin": "whatsapp_forward",
                  "sourceMessageRaw": "20%% off en pan dulce hasta fin de mes",
                  "photoUrl": "/uploads/business-%d/foto.jpg"
                }
                """.formatted(businessId, businessId)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.origin").value("whatsapp_forward"))
        .andReturn();

    Integer offerId = JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    mockMvc.perform(get("/api/offers/{id}", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"));
  }

  @Test
  void aprobarSinFechasAplicaDefaultDeCatorceDiasDesdeLaAprobacion() throws Exception {
    Integer businessId = createBusiness("098222002");
    Integer offerId = createOffer(businessId, "");

    OffsetDateTime before = OffsetDateTime.now();
    MvcResult res = mockMvc.perform(post("/api/offers/{id}/approve", offerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andReturn();
    OffsetDateTime after = OffsetDateTime.now();

    String validUntilRaw = JsonPath.read(res.getResponse().getContentAsString(), "$.validUntil");
    OffsetDateTime validUntil = OffsetDateTime.parse(validUntilRaw, DateTimeFormatter.ISO_OFFSET_DATE_TIME);

    // Nunca puede quedar ACTIVE sin validUntil, y el default es ~14 días
    // desde el momento de la aprobación (rango generoso para no ser flaky).
    assertThat(validUntil).isAfter(before.plusDays(13).plusHours(23));
    assertThat(validUntil).isBefore(after.plusDays(14).plusHours(1));
  }

  @Test
  void aprobarConValidFromCargadoUsaEseComoBaseDeLosCatorceDias() throws Exception {
    Integer businessId = createBusiness("098222003");
    String validFrom = "2026-01-01T00:00:00Z";
    Integer offerId = createOffer(businessId, ", \"validFrom\": \"%s\"".formatted(validFrom));

    MvcResult res = mockMvc.perform(post("/api/offers/{id}/approve", offerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();

    String validUntilRaw = JsonPath.read(res.getResponse().getContentAsString(), "$.validUntil");
    OffsetDateTime validUntil = OffsetDateTime.parse(validUntilRaw, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    assertThat(validUntil).isEqualTo(OffsetDateTime.parse(validFrom).plusDays(14));
  }

  @Test
  void aprobarConValidUntilYaCargadoNoLoPisa() throws Exception {
    Integer businessId = createBusiness("098222004");
    String validUntil = "2026-03-01T00:00:00Z";
    Integer offerId = createOffer(businessId, ", \"validUntil\": \"%s\"".formatted(validUntil));

    mockMvc.perform(post("/api/offers/{id}/approve", offerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.validUntil").value(org.hamcrest.Matchers.startsWith("2026-03-01")));
  }

  @Test
  void noSePuedeAprobarUnaOfertaQueYaNoEstaEnDraft() throws Exception {
    Integer businessId = createBusiness("098222005");
    Integer offerId = createOffer(businessId, "");

    mockMvc.perform(post("/api/offers/{id}/approve", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/offers/{id}/approve", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isConflict());
  }

  @Test
  void rechazarPasaDraftARejected() throws Exception {
    Integer businessId = createBusiness("098222006");
    Integer offerId = createOffer(businessId, "");

    mockMvc.perform(post("/api/offers/{id}/reject", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    mockMvc.perform(get("/api/offers/{id}", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  @Test
  void noSePuedeRechazarUnaOfertaYaAprobada() throws Exception {
    Integer businessId = createBusiness("098222007");
    Integer offerId = createOffer(businessId, "");

    mockMvc.perform(post("/api/offers/{id}/approve", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/offers/{id}/reject", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isConflict());
  }

  @Test
  void colaDeAprobacionMuestraSourceMessageRawYPhotoUrlDeLosDraftsDelTest() throws Exception {
    Integer businessId = createBusiness("098222008");
    Integer draftId = createOffer(businessId,
        ", \"origin\": \"whatsapp_forward\", \"sourceMessageRaw\": \"texto original del comercio\", \"photoUrl\": \"/uploads/foto-cola.jpg\"");
    Integer approvedId = createOffer(businessId, "");
    mockMvc.perform(post("/api/offers/{id}/approve", approvedId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());

    MvcResult res = mockMvc.perform(get("/api/offers").param("status", "draft")
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    java.util.List<Integer> draftIds = JsonPath.read(body, "$[*].id");
    assertThat(draftIds).contains(draftId).doesNotContain(approvedId);

    String entryPath = "$[?(@.id == %d)]".formatted(draftId);
    java.util.List<Object> matches = JsonPath.read(body, entryPath);
    assertThat(matches).hasSize(1);
    java.util.Map<?, ?> entry = (java.util.Map<?, ?>) matches.get(0);
    assertThat(entry.get("sourceMessageRaw")).isEqualTo("texto original del comercio");
    assertThat(entry.get("photoUrl")).isEqualTo("/uploads/foto-cola.jpg");
  }

  @Test
  void patchEditaContenidoPeroNoPuedeCambiarStatus() throws Exception {
    Integer businessId = createBusiness("098222009");
    Integer offerId = createOffer(businessId, "");

    // OfferUpdateRequest no tiene campo status: aunque el JSON lo mande, el
    // deserializador simplemente lo ignora (no hay setter/parámetro que lo reciba).
    mockMvc.perform(patch("/api/offers/{id}", offerId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"discountText": "30% off", "status": "active"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.discountText").value("30% off"))
        .andExpect(jsonPath("$.status").value("DRAFT"));
  }
}
