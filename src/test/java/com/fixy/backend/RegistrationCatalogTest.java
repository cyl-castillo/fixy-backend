package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code GET /api/public/catalog/registration} (Fase 1+2 "puerta única de
 * registro"): las dos listas que necesita el wizard de alta —
 * providerCategories (ServiceCategory sin "otro") y businessCategories
 * (BusinessCategory completo, incluyendo los 4 legacy).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationCatalogTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void devuelveLasDosListasConLaFormaEsperada() throws Exception {
    MvcResult res = mockMvc.perform(get("/api/public/catalog/registration"))
        .andExpect(status().isOk())
        .andReturn();
    String body = res.getResponse().getContentAsString();

    List<Map<String, Object>> providerCategories = JsonPath.read(body, "$.providerCategories");
    List<Map<String, Object>> businessCategories = JsonPath.read(body, "$.businessCategories");

    assertThat(providerCategories).isNotEmpty();
    assertThat(businessCategories).isNotEmpty();

    // Cada item es {id, label}.
    assertThat(providerCategories.get(0)).containsKeys("id", "label");
    assertThat(businessCategories.get(0)).containsKeys("id", "label");
  }

  @Test
  void providerCategoriesExcluyeOtro_mismoFiltroQueElRegistroDeProveedores() throws Exception {
    MvcResult res = mockMvc.perform(get("/api/public/catalog/registration")).andReturn();
    List<String> ids = JsonPath.read(res.getResponse().getContentAsString(), "$.providerCategories[*].id");

    assertThat(ids).doesNotContain("otro");
    assertThat(ids).contains("plomeria", "electricidad", "pasteleria");
  }

  @Test
  void businessCategoriesIncluyeLosCuatroLegacyYLosNuevosDeBarrio() throws Exception {
    MvcResult res = mockMvc.perform(get("/api/public/catalog/registration")).andReturn();
    List<String> ids = JsonPath.read(res.getResponse().getContentAsString(), "$.businessCategories[*].id");

    // Legacy — ya usados por el wizard público de ofertas y en catalog.ts del frontend.
    assertThat(ids).contains("gastronomia", "tienda", "servicios", "otro");
    // Nuevos rubros de barrio de Ciudad de la Costa.
    assertThat(ids).contains(
        "panaderia", "carniceria", "verduleria", "almacen", "kiosco",
        "ferreteria", "farmacia", "mascotas", "belleza");
    assertThat(ids).hasSize(13);
  }

  @Test
  void esPublicoSinAutenticacion() throws Exception {
    mockMvc.perform(get("/api/public/catalog/registration"))
        .andExpect(status().isOk());
  }
}
