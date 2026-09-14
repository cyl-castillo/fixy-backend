package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.ServiceCatalogItem;
import com.fixy.backend.repository.ServiceCatalogItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogo de servicios con precio cerrado (Refundación fase 1, contrato
 * §2): {@code GET /api/public/catalog/services} (público) + CRUD admin
 * ({@code /api/services}, httpBasic). V28 no corre en test (flyway off), así
 * que cada test inserta su propia fila — sin semilla implícita.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ServiceCatalogTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ServiceCatalogItemRepository repository;

  private ServiceCatalogItem persist(String category, String code, String name, int priceFrom, boolean active, int sortOrder) {
    ServiceCatalogItem item = new ServiceCatalogItem();
    item.setCategory(category);
    item.setCode(code);
    item.setName(name);
    item.setPriceFrom(priceFrom);
    item.setActive(active);
    item.setSortOrder(sortOrder);
    return repository.save(item);
  }

  @Test
  void catalogoPublicoSoloTraeCategoriasActivasYServiciosActivosOrdenados() throws Exception {
    persist("plomeria", "svc-test-visita", "Visita de plomería", 700, true, 1);
    persist("plomeria", "svc-test-destape", "Destape", 1900, true, 0);
    persist("plomeria", "svc-test-inactivo", "Servicio inactivo", 500, false, 2);
    // jardineria no está en fixy.orders.active-categories (default) -> no debe aparecer.
    persist("jardineria", "svc-test-jardin", "Corte de pasto", 1200, true, 0);

    mockMvc.perform(get("/api/public/catalog/services"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.category=='jardineria')]").isEmpty())
        .andExpect(jsonPath("$[?(@.category=='plomeria')].services[?(@.code=='svc-test-inactivo')]").isEmpty())
        .andExpect(jsonPath("$[?(@.category=='plomeria')].services[0].code").value("svc-test-destape"))
        .andExpect(jsonPath("$[?(@.category=='plomeria')].services[1].code").value("svc-test-visita"));
  }

  @Test
  void catalogoPublicoFiltraPorCategoriaEnElQueryParam() throws Exception {
    persist("barometrica", "svc-test-pozo", "Vaciado de pozo", 3200, true, 0);
    persist("plomeria", "svc-test-otro", "Otro servicio", 700, true, 0);

    mockMvc.perform(get("/api/public/catalog/services").param("category", "barometrica"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].category").value("barometrica"));
  }

  @Test
  void esPublicoSinAutenticacion() throws Exception {
    mockMvc.perform(get("/api/public/catalog/services")).andExpect(status().isOk());
  }

  @Test
  void adminListRequiereAutenticacion() throws Exception {
    mockMvc.perform(get("/api/services")).andExpect(status().isUnauthorized());
  }

  @Test
  void adminCreaYEditaUnServicio() throws Exception {
    String createBody = """
        {"category": "aires_acondicionados", "code": "svc-test-crear", "name": "Service de prueba",
         "description": "una linea", "priceFrom": 2900, "priceTo": 3500, "durationMin": 60}
        """;

    var createResult = mockMvc.perform(post("/api/services")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value("svc-test-crear"))
        .andExpect(jsonPath("$.active").value(true))
        .andReturn();

    Long id = ((Number) com.jayway.jsonpath.JsonPath.read(createResult.getResponse().getContentAsString(), "$.id")).longValue();

    mockMvc.perform(patch("/api/services/{id}", id)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceFrom\": 3100, \"active\": false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priceFrom").value(3100))
        .andExpect(jsonPath("$.active").value(false));

    ServiceCatalogItem updated = repository.findById(id).orElseThrow();
    assertThat(updated.getPriceFrom()).isEqualTo(3100);
    assertThat(updated.isActive()).isFalse();

    // Desactivado: ya no aparece en el catálogo público.
    mockMvc.perform(get("/api/public/catalog/services").param("category", "aires_acondicionados"))
        .andExpect(jsonPath("$[0].services[?(@.code=='svc-test-crear')]").isEmpty());
  }

  @Test
  void adminRechazaCodeDuplicado() throws Exception {
    persist("plomeria", "svc-test-dup", "Ya existe", 700, true, 0);

    mockMvc.perform(post("/api/services")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"category": "plomeria", "code": "svc-test-dup", "name": "Duplicado", "priceFrom": 800}
                """))
        .andExpect(status().isBadRequest());
  }
}
