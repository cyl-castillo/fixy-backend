package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * {@code PATCH /api/businesses/{id}} extendido con {@code description} y
 * {@code categories} (Fase 1 de la ficha, V24) — y el {@code business_event}
 * {@code FICHA_UPDATED} que registra la mutación.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BusinessFichaUpdateTest {

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
  void actualizaDescriptionYCategories() throws Exception {
    Long businessId = persistBusiness("Ferretería Ficha Update Test", "098444001");

    mockMvc.perform(patch("/api/businesses/{id}", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "description": "Ferretería de barrio, 30 años en la zona",
                  "categories": "ferretería, pinturería,  electricidad "
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value("Ferretería de barrio, 30 años en la zona"))
        .andExpect(jsonPath("$.categories").value("ferretería, pinturería, electricidad"));
  }

  @Test
  void updateConCambiosRealesRegistraEventoFichaUpdated() throws Exception {
    Long businessId = persistBusiness("Ferretería Ficha Evento Test", "098444002");

    mockMvc.perform(patch("/api/businesses/{id}", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "description": "Abrimos todos los días" }
                """))
        .andExpect(status().isOk());

    MvcResult res = mockMvc.perform(get("/api/businesses/{id}/events", businessId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();

    List<String> types = JsonPath.read(res.getResponse().getContentAsString(), "$[*].type");
    assertThat(types).contains("FICHA_UPDATED");
    String message = JsonPath.read(res.getResponse().getContentAsString(), "$[0].message");
    assertThat(message).contains("description");
  }

  @Test
  void updateSinCambiosRealesNoRegistraEvento() throws Exception {
    Long businessId = persistBusiness("Ferretería Ficha Sin Cambios Test", "098444003");

    // primer PATCH: efectivamente cambia primaryZone.
    mockMvc.perform(patch("/api/businesses/{id}", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "primaryZone": "Solymar" }
                """))
        .andExpect(status().isOk());

    MvcResult afterFirst = mockMvc.perform(get("/api/businesses/{id}/events", businessId)
            .with(httpBasic("test-ops", "test-pass")))
        .andReturn();
    int countAfterFirst = ((List<?>) JsonPath.read(afterFirst.getResponse().getContentAsString(), "$")).size();
    assertThat(countAfterFirst).isEqualTo(1);

    // segundo PATCH: mismo valor, no es un cambio real -> no debería sumar otro evento.
    mockMvc.perform(patch("/api/businesses/{id}", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "primaryZone": "Solymar" }
                """))
        .andExpect(status().isOk());

    MvcResult afterSecond = mockMvc.perform(get("/api/businesses/{id}/events", businessId)
            .with(httpBasic("test-ops", "test-pass")))
        .andReturn();
    int countAfterSecond = ((List<?>) JsonPath.read(afterSecond.getResponse().getContentAsString(), "$")).size();
    assertThat(countAfterSecond).isEqualTo(1);
  }

  @Test
  void eventosVienenEnOrdenDescendente() throws Exception {
    Long businessId = persistBusiness("Ferretería Ficha Orden Test", "098444004");

    mockMvc.perform(patch("/api/businesses/{id}", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "primaryZone": "Solymar" }
                """))
        .andExpect(status().isOk());
    mockMvc.perform(patch("/api/businesses/{id}", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "primaryZone": "Lagomar" }
                """))
        .andExpect(status().isOk());

    MvcResult res = mockMvc.perform(get("/api/businesses/{id}/events", businessId)
            .with(httpBasic("test-ops", "test-pass")))
        .andReturn();
    List<String> messages = JsonPath.read(res.getResponse().getContentAsString(), "$[*].message");
    assertThat(messages.get(0)).contains("Lagomar");
    assertThat(messages.get(1)).contains("Solymar");
  }
}
