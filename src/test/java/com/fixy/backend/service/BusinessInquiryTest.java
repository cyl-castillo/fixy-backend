package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessCatalogItem;
import com.fixy.backend.model.BusinessCatalogItemConfidence;
import com.fixy.backend.model.BusinessCatalogItemKind;
import com.fixy.backend.model.BusinessInquiry;
import com.fixy.backend.model.BusinessInquiryStatus;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.PushSubscription;
import com.fixy.backend.repository.BusinessCatalogItemRepository;
import com.fixy.backend.repository.BusinessInquiryRepository;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.PushSubscriptionRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Motor de respuesta con escalado al dueño (Fase 2, gap analysis 2026-08-25
 * §2): flujo completo HTTP+H2 — pregunta auto-respondida, escalada,
 * respondida por el dueño (con upsert al catálogo), token inválido, doble
 * respuesta y el hueco de contrato de push tardío. Cada test usa su propio
 * comercio con nombre/whatsapp únicos (H2 compartida entre contextos).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BusinessInquiryTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private BusinessRepository businessRepository;
  @Autowired private BusinessCatalogItemRepository catalogItemRepository;
  @Autowired private BusinessInquiryRepository businessInquiryRepository;
  @Autowired private PushSubscriptionRepository pushSubscriptionRepository;

  private Business persistBusiness(String tag) {
    Business business = new Business();
    business.setName("Comercio Inquiry Motor Test " + tag);
    business.setWhatsappNumber("0977" + tag);
    business.setCategory("ferretería");
    business.setStatus(BusinessStatus.ACTIVE);
    business.setPanelToken("inquiry-motor-token-" + tag);
    return businessRepository.save(business);
  }

  private BusinessCatalogItem persistItem(
      Business business, String label, BusinessCatalogItemConfidence confidence, boolean available
  ) {
    BusinessCatalogItem item = new BusinessCatalogItem();
    item.setBusiness(business);
    item.setLabel(label);
    item.setKind(BusinessCatalogItemKind.PRODUCT);
    item.setConfidence(confidence);
    item.setAvailable(available);
    item.setActive(true);
    if (confidence == BusinessCatalogItemConfidence.CONFIRMADO) {
      // Mismo criterio que BusinessCatalogItemService: un ítem CONFIRMADO trae verifiedAt.
      item.setVerifiedAt(java.time.OffsetDateTime.now());
    }
    return catalogItemRepository.save(item);
  }

  private String createBody(String question, String visitorName, String visitorWhatsapp, String pushEndpoint, String website) {
    return """
        {"question": %s, "visitorName": %s, "visitorWhatsapp": %s, "pushEndpoint": %s, "website": %s}
        """.formatted(json(question), json(visitorName), json(visitorWhatsapp), json(pushEndpoint), json(website));
  }

  private String json(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  // --- POST /api/public/businesses/{businessId}/inquiries: respuesta automática ---

  @Test
  void preguntaConItemConfirmadoDisponible_respondeAutoSi() throws Exception {
    Business business = persistBusiness("001");
    persistItem(business, "Cemento Portland", BusinessCatalogItemConfidence.CONFIRMADO, true);

    mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("¿Tienen cemento portland?", "Vecina Test", "099111001", null, null)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ANSWERED_AUTO"))
        .andExpect(jsonPath("$.inquiryId").exists())
        .andExpect(jsonPath("$.accessToken").doesNotExist())
        .andExpect(jsonPath("$.answer.value").value("SI"))
        .andExpect(jsonPath("$.answer.businessName").value(business.getName()));

    List<BusinessInquiry> saved = businessInquiryRepository.findByBusinessIdAndStatusOrderByCreatedAtDesc(
        business.getId(), BusinessInquiryStatus.ANSWERED_AUTO);
    assertThat(saved).hasSize(1);
    assertThat(saved.get(0).getAnswerSource()).isEqualTo(BusinessInquiry.SOURCE_CATALOG);
  }

  @Test
  void preguntaConItemConfirmadoNoDisponible_respondeAutoNo() throws Exception {
    Business business = persistBusiness("002");
    persistItem(business, "Taladro Bosch", BusinessCatalogItemConfidence.CONFIRMADO, false);

    mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("tenes taladro bosch?", "Vecina Test", "099111002", null, null)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ANSWERED_AUTO"))
        .andExpect(jsonPath("$.answer.value").value("NO"));
  }

  // --- POST: escalado ---

  @Test
  void preguntaSinMatchEnElCatalogo_escalaYDevuelveAccessToken() throws Exception {
    Business business = persistBusiness("003");

    MvcResult res = mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("¿Tienen sillas de jardín?", "Vecina Test", "099111003", null, null)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ESCALATED"))
        .andExpect(jsonPath("$.accessToken").exists())
        .andExpect(jsonPath("$.answer").doesNotExist())
        .andReturn();

    Integer inquiryId = JsonPath.read(res.getResponse().getContentAsString(), "$.inquiryId");
    BusinessInquiry saved = businessInquiryRepository.findById(inquiryId.longValue()).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(BusinessInquiryStatus.ESCALATED);
    assertThat(saved.getOwnerNotifiedAt()).isNotNull();
    // ensurePanelLink genera el token si el comercio no lo tenía (acá ya lo tenía).
    assertThat(businessRepository.findById(business.getId()).orElseThrow().getPanelToken()).isNotNull();
  }

  @Test
  void soloItemInferido_escalaIgual() throws Exception {
    Business business = persistBusiness("004");
    persistItem(business, "Cerámica San Lorenzo", BusinessCatalogItemConfidence.INFERIDO, true);

    mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("tenes ceramica san lorenzo?", "Vecina Test", "099111004", null, null)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ESCALATED"));
  }

  // --- POST: validaciones y honeypot ---

  @Test
  void honeypotNoPersisteYResponde201Igual() throws Exception {
    Business business = persistBusiness("005");

    mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("x", "Bot", "000", null, "http://bot.example")))
        .andExpect(status().isCreated());

    assertThat(businessInquiryRepository.findByBusinessIdAndStatusOrderByCreatedAtDesc(
        business.getId(), BusinessInquiryStatus.ESCALATED)).isEmpty();
    assertThat(businessInquiryRepository.findByBusinessIdAndStatusOrderByCreatedAtDesc(
        business.getId(), BusinessInquiryStatus.ANSWERED_AUTO)).isEmpty();
  }

  @Test
  void businessInexistenteDevuelve404() throws Exception {
    mockMvc.perform(post("/api/public/businesses/{id}/inquiries", 999999)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("¿Tienen algo?", "Vecina Test", "099111005", null, null)))
        .andExpect(status().isNotFound());
  }

  @Test
  void preguntaMuyCortaDevuelve400() throws Exception {
    Business business = persistBusiness("006");

    mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("hi", null, null, null, null)))
        .andExpect(status().isBadRequest());
  }

  // --- GET /api/public/inquiries/{id}?token= ---

  @Test
  void visitorGetConTokenValido_devuelveLaConsulta() throws Exception {
    Business business = persistBusiness("007");
    persistItem(business, "Cemento Portland", BusinessCatalogItemConfidence.CONFIRMADO, true);

    MvcResult createRes = mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("tienen cemento portland?", "Vecina Test", "099111007", null, null)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer inquiryId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.inquiryId");
    BusinessInquiry saved = businessInquiryRepository.findById(inquiryId.longValue()).orElseThrow();

    mockMvc.perform(get("/api/public/inquiries/{id}", inquiryId).param("token", saved.getAccessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ANSWERED_AUTO"))
        .andExpect(jsonPath("$.answer").value("SI"))
        .andExpect(jsonPath("$.verifiedAt").exists())
        .andExpect(jsonPath("$.businessName").value(business.getName()));
  }

  @Test
  void visitorGetConTokenInvalido_es404Opaco() throws Exception {
    Business business = persistBusiness("008");
    MvcResult createRes = mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("¿Tienen algo raro?", "Vecina Test", "099111008", null, null)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer inquiryId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.inquiryId");

    mockMvc.perform(get("/api/public/inquiries/{id}", inquiryId).param("token", "token-que-no-es"))
        .andExpect(status().isNotFound());
  }

  @Test
  void visitorGetConIdInexistente_es404() throws Exception {
    mockMvc.perform(get("/api/public/inquiries/{id}", 999999).param("token", "cualquiera"))
        .andExpect(status().isNotFound());
  }

  // --- POST /api/public/merchant/{token}/inquiries/{id}/answer: el dueño contesta ---

  @Test
  void ownerContestaSiSinItemPrevio_creaItemNuevoConfirmadoDisponible() throws Exception {
    Business business = persistBusiness("009");
    MvcResult createRes = mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("¿Tenés detergente para pisos?", "Vecina Test", "099111009", null, null)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ESCALATED"))
        .andReturn();
    Integer inquiryId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.inquiryId");
    String panelToken = businessRepository.findById(business.getId()).orElseThrow().getPanelToken();

    mockMvc.perform(post("/api/public/merchant/{token}/inquiries/{inquiryId}/answer", panelToken, inquiryId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\": \"SI\", \"priceFrom\": 150}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ANSWERED_OWNER"))
        .andExpect(jsonPath("$.answer").value("SI"))
        .andExpect(jsonPath("$.catalogItemId").exists());

    BusinessInquiry updated = businessInquiryRepository.findById(inquiryId.longValue()).orElseThrow();
    assertThat(updated.getStatus()).isEqualTo(BusinessInquiryStatus.ANSWERED_OWNER);
    assertThat(updated.getAnswerSource()).isEqualTo(BusinessInquiry.SOURCE_OWNER);

    BusinessCatalogItem item = catalogItemRepository.findById(updated.getCatalogItemId()).orElseThrow();
    assertThat(item.getLabel()).isEqualTo("Detergente pisos");
    assertThat(item.getKind()).isEqualTo(BusinessCatalogItemKind.PRODUCT);
    assertThat(item.getConfidence()).isEqualTo(BusinessCatalogItemConfidence.CONFIRMADO);
    assertThat(item.isAvailable()).isTrue();
    assertThat(item.getPriceFrom()).isEqualTo(150);
    assertThat(item.getVerifiedAt()).isNotNull();
  }

  @Test
  void ownerContestaSobreItemInferido_loConfirmaYEstampaVerifiedAt() throws Exception {
    Business business = persistBusiness("011");
    BusinessCatalogItem existing = persistItem(business, "Cerámica San Lorenzo", BusinessCatalogItemConfidence.INFERIDO, true);
    assertThat(existing.getVerifiedAt()).isNull();

    MvcResult createRes = mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("tenes ceramica san lorenzo?", "Vecina Test", "099111011", null, null)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ESCALATED"))
        .andReturn();
    Integer inquiryId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.inquiryId");
    String panelToken = businessRepository.findById(business.getId()).orElseThrow().getPanelToken();

    mockMvc.perform(post("/api/public/merchant/{token}/inquiries/{inquiryId}/answer", panelToken, inquiryId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\": \"NO\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("NO"));

    BusinessCatalogItem updated = catalogItemRepository.findById(existing.getId()).orElseThrow();
    assertThat(updated.getConfidence()).isEqualTo(BusinessCatalogItemConfidence.CONFIRMADO);
    assertThat(updated.isAvailable()).isFalse();
    assertThat(updated.getVerifiedAt()).isNotNull();
    assertThat(updated.getLabel()).isEqualTo("Cerámica San Lorenzo");
  }

  @Test
  void ownerContestaDosVeces_segundaEs409() throws Exception {
    Business business = persistBusiness("012");
    MvcResult createRes = mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("¿Tienen alguna herramienta rara?", "Vecina Test", "099111012", null, null)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer inquiryId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.inquiryId");
    String panelToken = businessRepository.findById(business.getId()).orElseThrow().getPanelToken();

    mockMvc.perform(post("/api/public/merchant/{token}/inquiries/{inquiryId}/answer", panelToken, inquiryId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\": \"SI\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/merchant/{token}/inquiries/{inquiryId}/answer", panelToken, inquiryId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\": \"NO\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void ownerContestaConTokenInvalido_es404Opaco() throws Exception {
    Business business = persistBusiness("013");
    MvcResult createRes = mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("¿Tienen algo puntual?", "Vecina Test", "099111013", null, null)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer inquiryId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.inquiryId");

    mockMvc.perform(post("/api/public/merchant/{token}/inquiries/{inquiryId}/answer", "token-que-no-existe", inquiryId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\": \"SI\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void ownerContestaConValorInvalido_es400() throws Exception {
    Business business = persistBusiness("014");
    MvcResult createRes = mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("¿Tienen algo más?", "Vecina Test", "099111014", null, null)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer inquiryId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.inquiryId");
    String panelToken = businessRepository.findById(business.getId()).orElseThrow().getPanelToken();

    mockMvc.perform(post("/api/public/merchant/{token}/inquiries/{inquiryId}/answer", panelToken, inquiryId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\": \"TALVEZ\"}"))
        .andExpect(status().isBadRequest());
  }

  // --- panel: pendingInquiries ---

  @Test
  void panelDelComercioMuestraLaConsultaPendienteYDejaDeMostrarlaTrasContestar() throws Exception {
    Business business = persistBusiness("015");
    MvcResult createRes = mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("¿Tienen escaleras de aluminio?", "Vecina Panel Test", "099111015", null, null)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer inquiryId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.inquiryId");
    String panelToken = businessRepository.findById(business.getId()).orElseThrow().getPanelToken();

    mockMvc.perform(get("/api/public/merchant/{token}", panelToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendingInquiries[0].id").value(inquiryId))
        .andExpect(jsonPath("$.pendingInquiries[0].question").value("¿Tienen escaleras de aluminio?"))
        .andExpect(jsonPath("$.pendingInquiries[0].visitorName").value("Vecina Panel Test"));

    mockMvc.perform(post("/api/public/merchant/{token}/inquiries/{inquiryId}/answer", panelToken, inquiryId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\": \"NO\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/public/merchant/{token}", panelToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendingInquiries").isEmpty());
  }

  // --- PATCH /api/public/inquiries/{id}?token=: push tardío (hueco de contrato) ---

  @Test
  void patchAdjuntaPushEndpointTardioMientrasSigaEscalada() throws Exception {
    Business business = persistBusiness("016");
    MvcResult createRes = mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("¿Tienen algo especial?", "Vecina Test", "099111016", null, null)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer inquiryId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.inquiryId");
    String accessToken = JsonPath.read(createRes.getResponse().getContentAsString(), "$.accessToken");

    mockMvc.perform(patch("/api/public/inquiries/{id}", inquiryId)
            .param("token", accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pushEndpoint\": \"https://push.example/ep-tardio\"}"))
        .andExpect(status().isNoContent());

    BusinessInquiry updated = businessInquiryRepository.findById(inquiryId.longValue()).orElseThrow();
    assertThat(updated.getPushEndpoint()).isEqualTo("https://push.example/ep-tardio");
  }

  @Test
  void patchConTokenInvalido_es404Opaco() throws Exception {
    Business business = persistBusiness("017");
    MvcResult createRes = mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("¿Tienen algo más raro?", "Vecina Test", "099111017", null, null)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer inquiryId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.inquiryId");

    mockMvc.perform(patch("/api/public/inquiries/{id}", inquiryId)
            .param("token", "token-invalido")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pushEndpoint\": \"https://push.example/ep\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void patchTrasContestada_es409() throws Exception {
    Business business = persistBusiness("018");
    MvcResult createRes = mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("¿Tienen algo bien puntual?", "Vecina Test", "099111018", null, null)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer inquiryId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.inquiryId");
    String accessToken = JsonPath.read(createRes.getResponse().getContentAsString(), "$.accessToken");
    String panelToken = businessRepository.findById(business.getId()).orElseThrow().getPanelToken();

    mockMvc.perform(post("/api/public/merchant/{token}/inquiries/{inquiryId}/answer", panelToken, inquiryId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\": \"SI\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(patch("/api/public/inquiries/{id}", inquiryId)
            .param("token", accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pushEndpoint\": \"https://push.example/ep-tarde\"}"))
        .andExpect(status().isConflict());
  }

  // --- push al vecino tras la respuesta del dueño: no rompe el flujo aunque la sub sea de mentira ---

  @Test
  void ownerContestaConPushEndpointDelVecino_noRompeAunqueLaSubNoSirvaDeVerdad() throws Exception {
    Business business = persistBusiness("019");
    String endpoint = "https://push.example/ep-vecino-019";
    PushSubscription sub = new PushSubscription();
    sub.setEndpoint(endpoint);
    sub.setP256dh("dummy-p256dh-inquiry-test");
    sub.setAuth("dummy-auth-inquiry-test");
    pushSubscriptionRepository.save(sub);

    MvcResult createRes = mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("¿Tienen guantes de trabajo?", "Vecina Test", "099111019", endpoint, null)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer inquiryId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.inquiryId");
    String panelToken = businessRepository.findById(business.getId()).orElseThrow().getPanelToken();

    mockMvc.perform(post("/api/public/merchant/{token}/inquiries/{inquiryId}/answer", panelToken, inquiryId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\": \"SI\"}"))
        .andExpect(status().isOk());
  }
}
