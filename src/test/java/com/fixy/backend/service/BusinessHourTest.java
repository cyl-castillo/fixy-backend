package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Horarios de la ficha (Fase 1, V24) — {@code /api/businesses/{id}/hours}.
 * El {@code PUT} SIEMPRE reemplaza el set completo (ver
 * {@code BusinessHourService.replace}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BusinessHourTest {

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
    Long businessId = persistBusiness("Ferretería Hours Auth Test", "098333001");

    mockMvc.perform(get("/api/businesses/{id}/hours", businessId))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void reemplazaElSetCompletoConVariasFranjasPorDia() throws Exception {
    Long businessId = persistBusiness("Ferretería Hours Partido Test", "098333002");

    mockMvc.perform(put("/api/businesses/{id}/hours", businessId)
            .with(httpBasic("test-ops", "test-pass"))
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

    MvcResult res = mockMvc.perform(get("/api/businesses/{id}/hours", businessId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    List<Integer> days = JsonPath.read(res.getResponse().getContentAsString(), "$[*].dayOfWeek");
    assertThat(days).containsExactly(1, 1, 6);
  }

  @Test
  void putConSetVacioBorraTodasLasFranjas() throws Exception {
    Long businessId = persistBusiness("Ferretería Hours Vaciar Test", "098333003");

    mockMvc.perform(put("/api/businesses/{id}/hours", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                [ { "dayOfWeek": 1, "opensAt": "09:00", "closesAt": "18:00" } ]
                """))
        .andExpect(status().isOk());

    mockMvc.perform(put("/api/businesses/{id}/hours", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("[]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void dayOfWeekFueraDeRangoDevuelve400() throws Exception {
    Long businessId = persistBusiness("Ferretería Hours DayOfWeek Test", "098333004");

    mockMvc.perform(put("/api/businesses/{id}/hours", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                [ { "dayOfWeek": 8, "opensAt": "09:00", "closesAt": "18:00" } ]
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void formatoDeHoraInvalidoDevuelve400() throws Exception {
    Long businessId = persistBusiness("Ferretería Hours Formato Test", "098333005");

    mockMvc.perform(put("/api/businesses/{id}/hours", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                [ { "dayOfWeek": 1, "opensAt": "9:00", "closesAt": "18:00" } ]
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void opensAtNoAnteriorAClosesAtDevuelve400() throws Exception {
    Long businessId = persistBusiness("Ferretería Hours Orden Test", "098333006");

    mockMvc.perform(put("/api/businesses/{id}/hours", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                [ { "dayOfWeek": 1, "opensAt": "18:00", "closesAt": "09:00" } ]
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reemplazoRegistraEventoHoursUpdatedEnLaTimeline() throws Exception {
    Long businessId = persistBusiness("Ferretería Hours Timeline Test", "098333007");

    mockMvc.perform(put("/api/businesses/{id}/hours", businessId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                [ { "dayOfWeek": 3, "opensAt": "09:00", "closesAt": "18:00" } ]
                """))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/businesses/{id}/events", businessId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].type").value("HOURS_UPDATED"))
        .andExpect(jsonPath("$[0].actor").value("admin"));
  }
}
