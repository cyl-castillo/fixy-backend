package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.repository.BusinessRepository;
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
 * Panel self-service del comercio (Fase 2 del panel — el dueño edita su
 * ficha): {@code /api/public/merchant/{token}/catalog}, {@code
 * /api/public/merchant/{token}/hours} y {@code
 * /api/public/merchant/{token}/business} (descripción). Espejo de {@link
 * BusinessCatalogItemTest}/{@link BusinessHourTest} (mismos services admin
 * por debajo) + mismo criterio de 404 opaco de {@link
 * MerchantPanelSurfaceTest}. Cada test usa su propio comercio con {@code
 * panelToken} propio — mismo aislamiento que el resto de la suite (H2
 * compartida entre contextos).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MerchantPanelFichaEditTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private BusinessRepository businessRepository;

  private Business persistBusiness(String tag) {
    Business business = new Business();
    business.setName("Comercio Ficha Panel Test " + tag);
    business.setWhatsappNumber("0977" + tag);
    business.setCategory("ferretería");
    business.setPrimaryZone("Solymar");
    business.setStatus(BusinessStatus.ACTIVE);
    business.setPanelToken("panel-ficha-token-" + tag);
    return businessRepository.save(business);
  }

  // ---- catalog: token inválido ----

  @Test
  void getCatalogTokenInexistenteEs404Opaco() throws Exception {
    mockMvc.perform(get("/api/public/merchant/{token}/catalog", "no-existe-este-token"))
        .andExpect(status().isNotFound());
  }

  @Test
  void postCatalogTokenInexistenteEs404Opaco() throws Exception {
    mockMvc.perform(post("/api/public/merchant/{token}/catalog", "no-existe-este-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Algo", "kind": "PRODUCT", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isNotFound());
  }

  // ---- catalog: CRUD feliz + confianza forzada a CONFIRMADO ----

  @Test
  void postCatalogCreaElItemConConfianzaSiempreConfirmada() throws Exception {
    Business business = persistBusiness("101");

    // el dueño manda "DECLARADO" en el body pero el server fuerza CONFIRMADO
    // (el dueño es la autoridad sobre su propio catálogo).
    mockMvc.perform(post("/api/public/merchant/{token}/catalog", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Taladro Bosch GSB 13", "kind": "PRODUCT", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.label").value("Taladro Bosch GSB 13"))
        .andExpect(jsonPath("$.confidence").value("CONFIRMADO"))
        .andExpect(jsonPath("$.verifiedAt").exists())
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  void postCatalogRegistraEventoEnLaTimelineConActorOwner() throws Exception {
    Business business = persistBusiness("102");

    mockMvc.perform(post("/api/public/merchant/{token}/catalog", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Cemento Portland", "kind": "PRODUCT", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isCreated());

    mockMvc.perform(get("/api/businesses/{id}/events", business.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].type").value("CATALOG_ITEM_ADDED"))
        .andExpect(jsonPath("$[0].actor").value("owner"));
  }

  @Test
  void getCatalogListaLoQueCreoElDueño() throws Exception {
    Business business = persistBusiness("103");

    mockMvc.perform(post("/api/public/merchant/{token}/catalog", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Pintura Sherwin Williams", "kind": "BRAND", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isCreated());

    mockMvc.perform(get("/api/public/merchant/{token}/catalog", business.getPanelToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].label").value("Pintura Sherwin Williams"));
  }

  @Test
  void putCatalogActualizaYLaConfianzaQuedaConfirmada() throws Exception {
    Business business = persistBusiness("104");

    MvcResult createRes = mockMvc.perform(post("/api/public/merchant/{token}/catalog", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Llave inglesa", "kind": "PRODUCT", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer itemId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.id");

    // el dueño manda "INFERIDO" pero igual queda CONFIRMADO.
    mockMvc.perform(put("/api/public/merchant/{token}/catalog/{itemId}", business.getPanelToken(), itemId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Llave inglesa 10\\"", "kind": "PRODUCT", "confidence": "INFERIDO", "active": true }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.label").value("Llave inglesa 10\""))
        .andExpect(jsonPath("$.confidence").value("CONFIRMADO"));
  }

  @Test
  void putCatalogItemDeOtroBusinessEs404OpacoYNoLoModifica() throws Exception {
    Business businessA = persistBusiness("105a");
    Business businessB = persistBusiness("105b");

    MvcResult createRes = mockMvc.perform(post("/api/public/merchant/{token}/catalog", businessB.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Producto de B", "kind": "PRODUCT", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer itemId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(put("/api/public/merchant/{token}/catalog/{itemId}", businessA.getPanelToken(), itemId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Intento de robo", "kind": "PRODUCT", "confidence": "CONFIRMADO", "active": true }
                """))
        .andExpect(status().isNotFound());

    mockMvc.perform(get("/api/public/merchant/{token}/catalog", businessB.getPanelToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].label").value("Producto de B"));
  }

  @Test
  void deleteCatalogItemDeOtroBusinessEs404Opaco() throws Exception {
    Business businessA = persistBusiness("106a");
    Business businessB = persistBusiness("106b");

    MvcResult createRes = mockMvc.perform(post("/api/public/merchant/{token}/catalog", businessB.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Producto de B", "kind": "PRODUCT", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer itemId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(delete("/api/public/merchant/{token}/catalog/{itemId}", businessA.getPanelToken(), itemId))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteCatalogEsSoftYEsIdempotente() throws Exception {
    Business business = persistBusiness("107");

    MvcResult createRes = mockMvc.perform(post("/api/public/merchant/{token}/catalog", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Escalera de aluminio", "kind": "PRODUCT", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer itemId = JsonPath.read(createRes.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(delete("/api/public/merchant/{token}/catalog/{itemId}", business.getPanelToken(), itemId))
        .andExpect(status().isOk());
    // idempotente: repetir el DELETE no falla.
    mockMvc.perform(delete("/api/public/merchant/{token}/catalog/{itemId}", business.getPanelToken(), itemId))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/public/merchant/{token}/catalog", business.getPanelToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == " + itemId + ")].active").value(org.hamcrest.Matchers.contains(false)));
  }

  @Test
  void postCatalogLabelVacioDevuelve400() throws Exception {
    Business business = persistBusiness("108");

    mockMvc.perform(post("/api/public/merchant/{token}/catalog", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "", "kind": "PRODUCT", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void postCatalogKindInvalidoDevuelve400() throws Exception {
    Business business = persistBusiness("109");

    mockMvc.perform(post("/api/public/merchant/{token}/catalog", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "label": "Algo", "kind": "SERVICIO", "confidence": "DECLARADO" }
                """))
        .andExpect(status().isBadRequest());
  }

  // ---- hours: token inválido ----

  @Test
  void getHoursTokenInexistenteEs404Opaco() throws Exception {
    mockMvc.perform(get("/api/public/merchant/{token}/hours", "no-existe-este-token"))
        .andExpect(status().isNotFound());
  }

  @Test
  void putHoursTokenInexistenteEs404Opaco() throws Exception {
    mockMvc.perform(put("/api/public/merchant/{token}/hours", "no-existe-este-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("[]"))
        .andExpect(status().isNotFound());
  }

  // ---- hours: GET/PUT feliz + validación ----

  @Test
  void putHoursReemplazaElSetCompleto() throws Exception {
    Business business = persistBusiness("110");

    mockMvc.perform(put("/api/public/merchant/{token}/hours", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                [
                  { "dayOfWeek": 1, "opensAt": "09:00", "closesAt": "12:30" },
                  { "dayOfWeek": 1, "opensAt": "14:00", "closesAt": "19:00", "note": "horario partido" },
                  { "dayOfWeek": 6, "opensAt": "09:00", "closesAt": "13:00" }
                ]
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));

    MvcResult res = mockMvc.perform(get("/api/public/merchant/{token}/hours", business.getPanelToken()))
        .andExpect(status().isOk())
        .andReturn();
    List<Integer> days = JsonPath.read(res.getResponse().getContentAsString(), "$[*].dayOfWeek");
    assertThat(days).containsExactly(1, 1, 6);

    // segundo PUT reemplaza el set anterior por completo.
    mockMvc.perform(put("/api/public/merchant/{token}/hours", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                [ { "dayOfWeek": 3, "opensAt": "10:00", "closesAt": "18:00" } ]
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].dayOfWeek").value(3));
  }

  @Test
  void putHoursOpensAtNoAnteriorAClosesAtDevuelve400() throws Exception {
    Business business = persistBusiness("111");

    mockMvc.perform(put("/api/public/merchant/{token}/hours", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                [ { "dayOfWeek": 1, "opensAt": "18:00", "closesAt": "09:00" } ]
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void putHoursRegistraEventoEnLaTimelineConActorOwner() throws Exception {
    Business business = persistBusiness("112");

    mockMvc.perform(put("/api/public/merchant/{token}/hours", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                [ { "dayOfWeek": 3, "opensAt": "09:00", "closesAt": "18:00" } ]
                """))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/businesses/{id}/events", business.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].type").value("HOURS_UPDATED"))
        .andExpect(jsonPath("$[0].actor").value("owner"));
  }

  // ---- description (PATCH business) ----

  @Test
  void patchDescriptionTokenInexistenteEs404Opaco() throws Exception {
    mockMvc.perform(patch("/api/public/merchant/{token}/business", "no-existe-este-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "description": "Somos una ferretería de barrio" }
                """))
        .andExpect(status().isNotFound());
  }

  @Test
  void patchDescriptionActualizaYLaDevuelveEnLaRespuesta() throws Exception {
    Business business = persistBusiness("113");

    mockMvc.perform(patch("/api/public/merchant/{token}/business", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "description": "Somos una ferretería de barrio, 20 años en Solymar" }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value("Somos una ferretería de barrio, 20 años en Solymar"));

    Business reloaded = businessRepository.findById(business.getId()).orElseThrow();
    assertThat(reloaded.getDescription()).isEqualTo("Somos una ferretería de barrio, 20 años en Solymar");
  }

  @Test
  void patchDescriptionNullBorraLaDescripcion() throws Exception {
    Business business = persistBusiness("114");
    business.setDescription("Descripción vieja");
    businessRepository.save(business);

    mockMvc.perform(patch("/api/public/merchant/{token}/business", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "description": null }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").doesNotExist());

    Business reloaded = businessRepository.findById(business.getId()).orElseThrow();
    assertThat(reloaded.getDescription()).isNull();
  }

  @Test
  void patchDescriptionMasDe500CaracteresDevuelve400() throws Exception {
    Business business = persistBusiness("115");
    String tooLong = "a".repeat(501);

    mockMvc.perform(patch("/api/public/merchant/{token}/business", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"description\":\"" + tooLong + "\"}"))
        .andExpect(status().isBadRequest());

    Business reloaded = businessRepository.findById(business.getId()).orElseThrow();
    assertThat(reloaded.getDescription()).isNull();
  }

  @Test
  void patchDescriptionExactamente500CaracteresEsValido() throws Exception {
    Business business = persistBusiness("116");
    String maxLength = "a".repeat(500);

    mockMvc.perform(patch("/api/public/merchant/{token}/business", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"description\":\"" + maxLength + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value(maxLength));
  }

  @Test
  void patchDescriptionNoPermiteTocarCategoriesNiOtrosCampos() throws Exception {
    Business business = persistBusiness("117");
    business.setCategories("plomería, electricidad");
    businessRepository.save(business);

    // el body manda categories además de description: el DTO del panel NO
    // tiene ese campo, así que Jackson lo ignora sin fallar.
    mockMvc.perform(patch("/api/public/merchant/{token}/business", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "description": "Nueva descripción", "categories": "otro-rubro-inventado" }
                """))
        .andExpect(status().isOk());

    Business reloaded = businessRepository.findById(business.getId()).orElseThrow();
    assertThat(reloaded.getCategories()).isEqualTo("plomería, electricidad");
  }

  @Test
  void patchDescriptionRegistraEventoEnLaTimelineConActorOwner() throws Exception {
    Business business = persistBusiness("118");

    mockMvc.perform(patch("/api/public/merchant/{token}/business", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "description": "Descripción nueva del dueño" }
                """))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/businesses/{id}/events", business.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].type").value("FICHA_UPDATED"))
        .andExpect(jsonPath("$[0].actor").value("owner"));
  }

  // ---- GET del panel incluye description (Fase 2, gap aditivo) ----

  @Test
  void getPanelIncluyeDescriptionNullSiNoTieneYValorSiLaCargo() throws Exception {
    Business business = persistBusiness("119");

    mockMvc.perform(get("/api/public/merchant/{token}", business.getPanelToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.business.description").doesNotExist());

    mockMvc.perform(patch("/api/public/merchant/{token}/business", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "description": "Ahora sí tengo descripción" }
                """))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/public/merchant/{token}", business.getPanelToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.business.description").value("Ahora sí tengo descripción"));
  }
}
