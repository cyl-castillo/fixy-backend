package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * CRUD admin de {@code Business} (diseño FIXY_OFERTAS_INGESTA_DESIGN.md §3.1,
 * historia adelantada por decisión de Carlos — ver bitácora del roadmap
 * 2026-08-10). Alta manual por ops nace ACTIVE directo, sin flujo de
 * aprobación previo (eso es propio de Offer, no de Business).
 */
@SpringBootTest
@AutoConfigureMockMvc
class BusinessCrudTest {

  @Autowired private org.springframework.test.web.servlet.MockMvc mockMvc;
  @Autowired private BusinessRepository businessRepository;

  private Integer createBusiness(String whatsapp) throws Exception {
    MvcResult res = mockMvc.perform(post("/api/businesses")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Panadería La Costa",
                  "whatsappNumber": "%s",
                  "category": "otro",
                  "primaryZone": "Solymar"
                }
                """.formatted(whatsapp)))
        .andExpect(status().isCreated())
        .andReturn();
    return JsonPath.read(res.getResponse().getContentAsString(), "$.id");
  }

  @Test
  void requiereAutenticacion() throws Exception {
    mockMvc.perform(get("/api/businesses"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void altaManualNaceActivaDirecto() throws Exception {
    Integer id = createBusiness("098111001");

    mockMvc.perform(get("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("ACTIVE"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.name").value("Panadería La Costa"));
  }

  @Test
  void editaCamposYVinculaProvider() throws Exception {
    Integer id = createBusiness("098111002");

    MvcResult res = mockMvc.perform(patch("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "primaryZone": "Lagomar",
                  "status": "INACTIVE",
                  "providerId": 999
                }
                """))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat((String) JsonPath.read(body, "$.primaryZone")).isEqualTo("Lagomar");
    assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("INACTIVE");
    assertThat((Integer) JsonPath.read(body, "$.providerId")).isEqualTo(999);
  }

  @Test
  void creaConAddressYLoDevuelveEnElResponse() throws Exception {
    MvcResult res = mockMvc.perform(post("/api/businesses")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Panadería Con Dirección",
                  "whatsappNumber": "098111005",
                  "category": "otro",
                  "primaryZone": "Solymar",
                  "address": "Av. Giannattasio Km 22.500"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();

    assertThat((String) JsonPath.read(res.getResponse().getContentAsString(), "$.address"))
        .isEqualTo("Av. Giannattasio Km 22.500");
  }

  @Test
  void creaConCoordenadasDescripcionYCategoriasYLasPersiste() throws Exception {
    // El form del admin manda todo esto en el alta; antes el create los
    // ignoraba en silencio y había que editar el comercio para cargarlos.
    MvcResult res = mockMvc.perform(post("/api/businesses")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Ferretería Ficha Completa",
                  "whatsappNumber": "098111015",
                  "category": "ferreteria",
                  "primaryZone": "Solymar",
                  "latitude": -34.8123,
                  "longitude": -55.9456,
                  "description": "Ferretería de barrio con pinturería",
                  "categories": "ferreteria-crud-create,pinturas-crud-create"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat((Double) JsonPath.read(body, "$.latitude")).isEqualTo(-34.8123);
    assertThat((Double) JsonPath.read(body, "$.longitude")).isEqualTo(-55.9456);
    assertThat((String) JsonPath.read(body, "$.description"))
        .isEqualTo("Ferretería de barrio con pinturería");
    assertThat((String) JsonPath.read(body, "$.categories"))
        .isEqualTo("ferreteria-crud-create,pinturas-crud-create");
  }

  @Test
  void altaSinAddressQuedaNull() throws Exception {
    Integer id = createBusiness("098111006");

    mockMvc.perform(get("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.address").doesNotExist());
  }

  @Test
  void editaLaAddressYLuegoLaBorraConStringVacio() throws Exception {
    Integer id = createBusiness("098111007");

    MvcResult setRes = mockMvc.perform(patch("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "address": "Ruta Interbalnearia Km 23"
                }
                """))
        .andExpect(status().isOk())
        .andReturn();
    assertThat((String) JsonPath.read(setRes.getResponse().getContentAsString(), "$.address"))
        .isEqualTo("Ruta Interbalnearia Km 23");

    // "   " se normaliza a null vía trimToNull, mismo patrón que el resto de campos texto.
    mockMvc.perform(patch("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "address": "   "
                }
                """))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.address").doesNotExist());
  }

  @Test
  void patchConLatitudeYLongitudeLasActualizaYNoTocaElRestoDeCampos() throws Exception {
    Integer id = createBusiness("098111008");

    MvcResult res = mockMvc.perform(patch("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "latitude": -34.812,
                  "longitude": -55.956
                }
                """))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat((Double) JsonPath.read(body, "$.latitude")).isEqualTo(-34.812);
    assertThat((Double) JsonPath.read(body, "$.longitude")).isEqualTo(-55.956);
    // semántica PATCH real: lo que no vino en el request no se toca.
    assertThat((String) JsonPath.read(body, "$.name")).isEqualTo("Panadería La Costa");
    assertThat((String) JsonPath.read(body, "$.primaryZone")).isEqualTo("Solymar");
  }

  @Test
  void patchSinLatitudeNiLongitudeNoLasPisaConNull() throws Exception {
    Integer id = createBusiness("098111009");

    mockMvc.perform(patch("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "latitude": -34.812,
                  "longitude": -55.956
                }
                """))
        .andExpect(status().isOk());

    // segundo PATCH que solo toca otro campo — las coordenadas ya cargadas sobreviven.
    MvcResult res = mockMvc.perform(patch("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "primaryZone": "Lagomar"
                }
                """))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat((Double) JsonPath.read(body, "$.latitude")).isEqualTo(-34.812);
    assertThat((Double) JsonPath.read(body, "$.longitude")).isEqualTo(-55.956);
    assertThat((String) JsonPath.read(body, "$.primaryZone")).isEqualTo("Lagomar");
  }

  @Test
  void altaSinCoordenadasQuedanNullEnElResponse() throws Exception {
    Integer id = createBusiness("098111010");

    mockMvc.perform(get("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.latitude").doesNotExist())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.longitude").doesNotExist());
  }

  @Test
  void altaSinPedirLinkQuedaConPanelTokenNull() throws Exception {
    Integer id = createBusiness("098111011");

    mockMvc.perform(get("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.panelToken").doesNotExist());
  }

  @Test
  void panelLinkEsLazyYDevuelveLaMismaUrlSiSePideDeNuevo() throws Exception {
    Integer id = createBusiness("098111012");

    MvcResult first = mockMvc.perform(post("/api/businesses/{id}/panel-link", id)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String firstUrl = JsonPath.read(first.getResponse().getContentAsString(), "$.url");
    assertThat(firstUrl).contains("/mi-comercio/");

    String tokenFromGet = JsonPath.read(
        mockMvc.perform(get("/api/businesses/{id}", id)
                .with(httpBasic("test-ops", "test-pass")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(),
        "$.panelToken");
    assertThat(firstUrl).endsWith("/mi-comercio/" + tokenFromGet);

    // Segundo pedido del link: NO regenera, devuelve exactamente la misma URL.
    MvcResult second = mockMvc.perform(post("/api/businesses/{id}/panel-link", id)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String secondUrl = JsonPath.read(second.getResponse().getContentAsString(), "$.url");
    assertThat(secondUrl).isEqualTo(firstUrl);
  }

  @Test
  void panelLinkRequiereAutenticacion() throws Exception {
    Integer id = createBusiness("098111013");

    mockMvc.perform(post("/api/businesses/{id}/panel-link", id))
        .andExpect(status().isUnauthorized());
  }

  // --- Fase 3 (V26): página pública del comercio ---

  @Test
  void altaNaceConSlugYaAsignado() throws Exception {
    // Nombre deliberadamente único en TODA la suite (no el de createBusiness,
    // compartido por decenas de tests de esta clase SIN @Transactional —
    // asertar un slug exacto sobre un nombre repetido sería frágil: el slug
    // real dependería del orden de ejecución y de si ya colisionó antes).
    MvcResult res = mockMvc.perform(post("/api/businesses")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Comercio Alta Con Slug Automatico Test",
                  "whatsappNumber": "098111014",
                  "category": "otro",
                  "primaryZone": "Solymar"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();

    assertThat((String) JsonPath.read(res.getResponse().getContentAsString(), "$.slug"))
        .isEqualTo("comercio-alta-con-slug-automatico-test");
  }

  @Test
  void publicLinkEsIdempotenteYDevuelveLaMismaUrlSiSePideDeNuevo() throws Exception {
    Integer id = createBusiness("098111016");

    MvcResult first = mockMvc.perform(post("/api/businesses/{id}/public-link", id)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String firstUrl = JsonPath.read(first.getResponse().getContentAsString(), "$.url");
    assertThat(firstUrl).contains("/comercio/");

    MvcResult second = mockMvc.perform(post("/api/businesses/{id}/public-link", id)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String secondUrl = JsonPath.read(second.getResponse().getContentAsString(), "$.url");
    assertThat(secondUrl).isEqualTo(firstUrl);
  }

  @Test
  void publicLinkRequiereAutenticacion() throws Exception {
    Integer id = createBusiness("098111017");

    mockMvc.perform(post("/api/businesses/{id}/public-link", id))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void listaIncluyeLosComerciosCreadosEnElTest() throws Exception {
    Integer a = createBusiness("098111003");
    Integer b = createBusiness("098111004");

    MvcResult res = mockMvc.perform(get("/api/businesses")
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();

    java.util.List<Integer> ids = JsonPath.read(res.getResponse().getContentAsString(), "$[*].id");
    assertThat(ids).contains(a, b);
  }

  // --- Fase 1+2 "puerta única de registro" (2026-08-27): catálogo de rubros ---

  @Test
  void altaConCategoriaFueraDelCatalogo_es400() throws Exception {
    mockMvc.perform(post("/api/businesses")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Comercio Categoria Invalida",
                  "whatsappNumber": "098111018",
                  "category": "categoria-inventada",
                  "primaryZone": "Solymar"
                }
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void altaConCategoriaDelCatalogo_persisteElIdCanonico() throws Exception {
    MvcResult res = mockMvc.perform(post("/api/businesses")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Panadería Catálogo Test",
                  "whatsappNumber": "098111019",
                  "category": "panaderia",
                  "primaryZone": "Solymar"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    assertThat((String) JsonPath.read(res.getResponse().getContentAsString(), "$.category"))
        .isEqualTo("panaderia");
  }

  @Test
  void patchCambiandoCategoryAUnaFueraDelCatalogo_es400() throws Exception {
    Integer id = createBusiness("098111020");

    mockMvc.perform(patch("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "category": "categoria-inventada" }
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void patchCambiandoCategoryAUnaDelCatalogo_seAplica() throws Exception {
    Integer id = createBusiness("098111021"); // nace con "otro"

    MvcResult res = mockMvc.perform(patch("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "category": "kiosco" }
                """))
        .andExpect(status().isOk())
        .andReturn();
    assertThat((String) JsonPath.read(res.getResponse().getContentAsString(), "$.category"))
        .isEqualTo("kiosco");
  }

  @Test
  void businessConCategoryLegacyInvalida_elPatchDeOtroCampoNoSeRompe() throws Exception {
    // Comercio con category fuera del catálogo nuevo (dato histórico real de
    // prod, ver CURRENT_WORK.md 2026-08-26: "supermercado", "cine", etc.) —
    // un PATCH que NO toca category debe seguir andando.
    Business business = new Business();
    business.setName("Comercio Legacy Category Test");
    business.setWhatsappNumber("098111022");
    business.setCategory("supermercado");
    business.setPrimaryZone("Solymar");
    business.setStatus(BusinessStatus.ACTIVE);
    Long id = businessRepository.save(business).getId();

    mockMvc.perform(patch("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "primaryZone": "Lagomar" }
                """))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.category").value("supermercado"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.primaryZone").value("Lagomar"));
  }

  @Test
  void businessConCategoryLegacyInvalida_reenviarLaMismaCategoryNoRompe() throws Exception {
    // Reenviar EXACTAMENTE el mismo valor legacy (no-op real) no debe 400 —
    // solo se exige catálogo cuando category REALMENTE cambia.
    Business business = new Business();
    business.setName("Comercio Legacy Category Idempotente Test");
    business.setWhatsappNumber("098111023");
    business.setCategory("estación de servicio");
    business.setPrimaryZone("Solymar");
    business.setStatus(BusinessStatus.ACTIVE);
    Long id = businessRepository.save(business).getId();

    mockMvc.perform(patch("/api/businesses/{id}", id)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "category": "estación de servicio" }
                """))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.category").value("estación de servicio"));
  }
}
