package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Alta pública de ofertas (fase 2 del roadmap "ofertas protagonistas"):
 * {@code POST /api/public/offer-submissions}. Cuello de botella #1 hasta
 * ahora era el admin de ops — esta puerta deja que el comerciante cargue su
 * primera oferta solo desde su celular, pero el pipeline de estados NO
 * cambia: nace {@code DRAFT} y solo ops la pasa a {@code ACTIVE}
 * ({@code OfferService.approve}). Ese es justamente el test clave de esta
 * clase: la oferta recién creada NO debe aparecer en
 * {@code /api/public/offers} hasta la aprobación.
 *
 * <p>Cada aserción filtra por los ids/nombres creados en ESTE test (H2
 * compartida entre contextos, no asumir tabla vacía — mismo criterio que
 * {@code OfferInquiryTest}/{@code PublicOfferSurfaceTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
    "fixy.cloudflare.api-token="
})
class PublicOfferSubmissionTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private BusinessRepository businessRepository;
  @Autowired private OfferRepository offerRepository;

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  private Map<String, Object> validPayload(String suffix) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("businessName", "Comercio Submission " + suffix);
    body.put("whatsappNumber", "09911" + suffix);
    body.put("category", "otro");
    body.put("zone", "Solymar");
    body.put("address", "Av. Giannattasio km 20");
    body.put("latitude", -34.79);
    body.put("longitude", -55.95);
    body.put("title", "Oferta submission " + suffix);
    body.put("discountText", "20% off");
    body.put("description", "Descripción de prueba del alta pública");
    return body;
  }

  private String json(Map<String, Object> body) throws Exception {
    return objectMapper.writeValueAsString(body);
  }

  @Test
  void altaFelizCreaBusinessYOfertaEnDraftYDevuelve201() throws Exception {
    Map<String, Object> payload = validPayload("001");

    MvcResult result = mockMvc.perform(post("/api/public/offer-submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(payload)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.offerId").exists())
        .andExpect(jsonPath("$.businessId").exists())
        .andReturn();

    Long offerId = ((Number) com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.offerId")).longValue();
    Long businessId = ((Number) com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.businessId")).longValue();

    Offer offer = offerRepository.findById(offerId).orElseThrow();
    assertThat(offer.getStatus()).isEqualTo(OfferStatus.DRAFT);
    assertThat(offer.getTitle()).isEqualTo("Oferta submission 001");
    assertThat(offer.getOrigin()).isEqualTo(Offer.ORIGIN_MANUAL);
    assertThat(offer.getBusinessId()).isEqualTo(businessId);

    Business business = businessRepository.findById(businessId).orElseThrow();
    assertThat(business.getName()).isEqualTo("Comercio Submission 001");
    assertThat(business.getWhatsappNumber()).isEqualTo("09911001");
    assertThat(business.getStatus()).isEqualTo(BusinessStatus.ACTIVE);
    assertThat(business.getProviderId()).isNull();
    assertThat(business.getAddress()).isEqualTo("Av. Giannattasio km 20");
    assertThat(business.getLatitude()).isEqualTo(-34.79);
    assertThat(business.getLongitude()).isEqualTo(-55.95);
  }

  @Test
  void whatsappExistenteReusaElBusinessYActualizaAddressYCoordenadas() throws Exception {
    Business existing = new Business();
    existing.setName("Comercio Preexistente Test");
    existing.setWhatsappNumber("099222333");
    existing.setCategory("otro");
    existing.setStatus(BusinessStatus.ACTIVE);
    existing = businessRepository.save(existing);

    Map<String, Object> payload = validPayload("002");
    // Mismo número con formato distinto (espacios) — misma normalización
    // que ProviderRegistrationService: debe matchear igual.
    payload.put("whatsappNumber", "099 222 333");
    payload.put("businessName", "Nombre Distinto Que No Debe Pisar");
    payload.put("address", "Nueva dirección cargada por el comerciante");
    payload.put("latitude", -34.80);
    payload.put("longitude", -55.96);

    MvcResult result = mockMvc.perform(post("/api/public/offer-submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(payload)))
        .andExpect(status().isCreated())
        .andReturn();

    Long businessId = ((Number) com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.businessId")).longValue();
    assertThat(businessId).isEqualTo(existing.getId());
    assertThat(businessRepository.count()).isGreaterThanOrEqualTo(1);

    Business reused = businessRepository.findById(businessId).orElseThrow();
    // Nombre original NO se pisa con el de la nueva submission.
    assertThat(reused.getName()).isEqualTo("Comercio Preexistente Test");
    assertThat(reused.getAddress()).isEqualTo("Nueva dirección cargada por el comerciante");
    assertThat(reused.getLatitude()).isEqualTo(-34.80);
    assertThat(reused.getLongitude()).isEqualTo(-55.96);
  }

  @Test
  void honeypotNoPersisteNiRompeYResponde201Igual() throws Exception {
    long businessCountBefore = businessRepository.count();
    long offerCountBefore = offerRepository.count();

    Map<String, Object> payload = validPayload("003");
    payload.put("website", "http://bot.example");

    mockMvc.perform(post("/api/public/offer-submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(payload)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"));

    assertThat(businessRepository.count()).isEqualTo(businessCountBefore);
    assertThat(offerRepository.count()).isEqualTo(offerCountBefore);
  }

  @Test
  void honeypotIgnoraCualquierOtroErrorDeValidacionYNoRompeElRequest() throws Exception {
    // businessName/whatsapp/category/zone/title vacíos (violarían las
    // validaciones obligatorias) + website lleno: el honeypot se chequea
    // PRIMERO, nunca debe llegar a 400.
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("businessName", "");
    payload.put("whatsappNumber", "");
    payload.put("category", "");
    payload.put("zone", "");
    payload.put("title", "");
    payload.put("website", "relleno-bot");

    mockMvc.perform(post("/api/public/offer-submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(payload)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"));
  }

  @Test
  void businessNameFaltanteEsRechazado() throws Exception {
    Map<String, Object> payload = validPayload("004");
    payload.remove("businessName");

    mockMvc.perform(post("/api/public/offer-submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(payload)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void whatsappFaltanteEsRechazado() throws Exception {
    Map<String, Object> payload = validPayload("005");
    payload.remove("whatsappNumber");

    mockMvc.perform(post("/api/public/offer-submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(payload)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void categoryFaltanteEsRechazada() throws Exception {
    Map<String, Object> payload = validPayload("006");
    payload.remove("category");

    mockMvc.perform(post("/api/public/offer-submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(payload)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void zoneFaltanteEsRechazada() throws Exception {
    Map<String, Object> payload = validPayload("007");
    payload.remove("zone");

    mockMvc.perform(post("/api/public/offer-submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(payload)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void titleFaltanteEsRechazado() throws Exception {
    Map<String, Object> payload = validPayload("008");
    payload.remove("title");

    mockMvc.perform(post("/api/public/offer-submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(payload)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void ningunoDeLosCamposInvalidosPersisteNada() throws Exception {
    long businessCountBefore = businessRepository.count();
    long offerCountBefore = offerRepository.count();

    Map<String, Object> payload = validPayload("009");
    payload.remove("title");

    mockMvc.perform(post("/api/public/offer-submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(payload)))
        .andExpect(status().isBadRequest());

    assertThat(businessRepository.count()).isEqualTo(businessCountBefore);
    assertThat(offerRepository.count()).isEqualTo(offerCountBefore);
  }

  /**
   * EL test clave del pipeline (contexto de la tarea): el alta pública SOLO
   * llega a DRAFT — la oferta recién cargada por el comerciante no debe
   * aparecer en la superficie pública de lectura hasta que ops la apruebe
   * explícitamente ({@code OfferService.approve}, vía
   * {@code POST /api/offers/{id}/approve}).
   */
  @Test
  void laOfertaNoApareceEnPublicOffersHastaQueOpsLaAprueba() throws Exception {
    Map<String, Object> payload = validPayload("010");
    payload.put("title", "Oferta Pipeline Aprobación Test");
    payload.put("zone", "Solymar");
    payload.put("category", "pipeline-test-category");

    MvcResult createResult = mockMvc.perform(post("/api/public/offer-submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(payload)))
        .andExpect(status().isCreated())
        .andReturn();
    Long offerId = ((Number) com.jayway.jsonpath.JsonPath.read(
        createResult.getResponse().getContentAsString(), "$.offerId")).longValue();

    // Antes de aprobar: no aparece en el listado público filtrado por zona,
    // ni en el detalle público (404).
    MvcResult listBefore = mockMvc.perform(get("/api/public/offers")
            .param("zone", "Solymar")
            .param("category", "pipeline-test-category"))
        .andExpect(status().isOk())
        .andReturn();
    List<String> titlesBefore = com.jayway.jsonpath.JsonPath.read(
        listBefore.getResponse().getContentAsString(), "$[*].title");
    assertThat(titlesBefore).doesNotContain("Oferta Pipeline Aprobación Test");

    mockMvc.perform(get("/api/public/offers/{id}", offerId))
        .andExpect(status().isNotFound());

    // Ops aprueba desde el admin.
    mockMvc.perform(post("/api/offers/{id}/approve", offerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    // Después de aprobar: sí aparece.
    MvcResult listAfter = mockMvc.perform(get("/api/public/offers")
            .param("zone", "Solymar")
            .param("category", "pipeline-test-category"))
        .andExpect(status().isOk())
        .andReturn();
    List<String> titlesAfter = com.jayway.jsonpath.JsonPath.read(
        listAfter.getResponse().getContentAsString(), "$[*].title");
    assertThat(titlesAfter).contains("Oferta Pipeline Aprobación Test");

    mockMvc.perform(get("/api/public/offers/{id}", offerId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Oferta Pipeline Aprobación Test"));
  }
}
