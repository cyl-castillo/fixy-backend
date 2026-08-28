package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogo estructurado de la ficha (Fase 1, V24) — {@code
 * /api/businesses/{id}/catalog}. Ver diseño en el gap analysis 2026-08-25
 * §1: la regla central es cuándo se estampa {@code verifiedAt}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BusinessCatalogItemTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private BusinessRepository businessRepository;

  private Long persistBusiness(String name, String whatsapp) {
    Business business = new Business();
    business.setName(name);
    business.setWhatsappNumber(whatsapp);
    business.setCategory("ferretería");
    business.setStatus(BusinessStatus.ACTIVE);
    return businessRepository.save(business).getId();
  }

  @Test
  void requiereAutenticacion() throws Exception {
    Long businessId = persistBusiness("Ferretería Catalog Auth Test", "098222001");

    mockMvc.perform(get("/api/businesses/{id}/catalog", businessId))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void creaUnItemDeclaradoSinVerifiedAt() throws Exception {
    Long businessId = persistBusiness("Ferretería Catalog Declarado Test", "098222002");

    MvcResult res = mockMvc.perform(post("/api/businesses/{id}/catalog", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Pintura Sherwin Williams", "kind": "BRAND", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.label").value("Pintura Sherwin Williams"))
        .andExpect(jsonPath("$.kind").value("BRAND"))
        .andExpect(jsonPath("$.confidence").value("DECLARADO"))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.verifiedAt").doesNotExist())
        .andReturn();

    Integer itemId = JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    assertThat(itemId).isNotNull();
  }

  @Test
  void crearConConfidenceConfirmadoEstampaVerifiedAt() throws Exception {
    Long businessId = persistBusiness("Ferretería Catalog Confirmado Test", "098222003");

    mockMvc.perform(post("/api/businesses/{id}/catalog", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Taladro Bosch GSB 13", "kind": "PRODUCT", "confidence": "CONFIRMADO" }
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.verifiedAt").exists());
  }

  @Test
  void updateQuePasaAConfirmadoEstampaVerifiedAt() throws Exception {
    Long businessId = persistBusiness("Ferretería Catalog Transicion Test", "098222004");

    MvcResult createRes = mockMvc.perform(post("/api/businesses/{id}/catalog", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Cemento Portland", "kind": "PRODUCT", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.verifiedAt").doesNotExist())
        .andReturn();
    Integer itemId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(put("/api/businesses/{id}/catalog/{itemId}", businessId, itemId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Cemento Portland", "kind": "PRODUCT", "confidence": "CONFIRMADO", "active": true }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confidence").value("CONFIRMADO"))
        .andExpect(jsonPath("$.verifiedAt").exists());
  }

  @Test
  void updateQueDejaDeSerConfirmadoNoBorraVerifiedAt() throws Exception {
    Long businessId = persistBusiness("Ferretería Catalog Historico Test", "098222005");

    MvcResult createRes = mockMvc.perform(post("/api/businesses/{id}/catalog", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Llave inglesa 10\\"", "kind": "PRODUCT", "confidence": "CONFIRMADO" }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer itemId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.id");
    String verifiedAtAlCrear = JsonPath.read(createRes.getResponse().getContentAsString(), "$.verifiedAt");
    assertThat(verifiedAtAlCrear).isNotNull();

    MvcResult updateRes = mockMvc.perform(put("/api/businesses/{id}/catalog/{itemId}", businessId, itemId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Llave inglesa 10\\"", "kind": "PRODUCT", "confidence": "INFERIDO", "active": true }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confidence").value("INFERIDO"))
        .andReturn();

    // el histórico de la última verificación NO se borra al dejar de ser CONFIRMADO.
    String verifiedAtTrasCambio = JsonPath.read(updateRes.getResponse().getContentAsString(), "$.verifiedAt");
    assertThat(verifiedAtTrasCambio).isEqualTo(verifiedAtAlCrear);
  }

  @Test
  void deleteEsSoftYQuedaVisibleConActiveFalseEnElListado() throws Exception {
    Long businessId = persistBusiness("Ferretería Catalog Delete Test", "098222006");

    MvcResult createRes = mockMvc.perform(post("/api/businesses/{id}/catalog", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Escalera de aluminio", "kind": "PRODUCT", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer itemId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(delete("/api/businesses/{id}/catalog/{itemId}", businessId, itemId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());

    // idempotente: repetir el DELETE no falla.
    mockMvc.perform(delete("/api/businesses/{id}/catalog/{itemId}", businessId, itemId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/businesses/{id}/catalog", businessId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == " + itemId + ")].active").value(org.hamcrest.Matchers.contains(false)));
  }

  @Test
  void kindInvalidoDevuelve400() throws Exception {
    Long businessId = persistBusiness("Ferretería Catalog Kind Invalido Test", "098222007");

    mockMvc.perform(post("/api/businesses/{id}/catalog", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Algo", "kind": "SERVICIO", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void confidenceInvalidoDevuelve400() throws Exception {
    Long businessId = persistBusiness("Ferretería Catalog Confidence Invalido Test", "098222008");

    mockMvc.perform(post("/api/businesses/{id}/catalog", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Algo", "kind": "PRODUCT", "confidence": "SEGURO" }
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void priceFromNegativoDevuelve400() throws Exception {
    Long businessId = persistBusiness("Ferretería Catalog Price Test", "098222009");

    mockMvc.perform(post("/api/businesses/{id}/catalog", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Algo", "kind": "PRODUCT", "confidence": "DECLARADO", "priceFrom": -10 }
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void labelVacioDevuelve400() throws Exception {
    Long businessId = persistBusiness("Ferretería Catalog Label Test", "098222010");

    mockMvc.perform(post("/api/businesses/{id}/catalog", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "  ", "kind": "PRODUCT", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void crearYActualizarRegistranEventosEnLaTimeline() throws Exception {
    Long businessId = persistBusiness("Ferretería Catalog Timeline Test", "098222011");

    MvcResult createRes = mockMvc.perform(post("/api/businesses/{id}/catalog", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Clavos 2 pulgadas", "kind": "PRODUCT", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer itemId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(put("/api/businesses/{id}/catalog/{itemId}", businessId, itemId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Clavos 2 pulgadas", "kind": "PRODUCT", "confidence": "CONFIRMADO", "active": true }
                """))
        .andExpect(status().isOk());

    mockMvc.perform(delete("/api/businesses/{id}/catalog/{itemId}", businessId, itemId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());

    MvcResult eventsRes = mockMvc.perform(get("/api/businesses/{id}/events", businessId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();

    java.util.List<String> types = JsonPath.read(eventsRes.getResponse().getContentAsString(), "$[*].type");
    assertThat(types).contains("CATALOG_ITEM_ADDED", "CATALOG_ITEM_UPDATED", "CATALOG_ITEM_REMOVED");
  }
}
