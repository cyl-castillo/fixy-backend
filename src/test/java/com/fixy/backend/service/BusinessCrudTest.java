package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
}
