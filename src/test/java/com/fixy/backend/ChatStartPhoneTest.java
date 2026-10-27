package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.repository.LeadRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Fase 1 (2026-10-27): el paso a paso del home captura el WhatsApp antes de
 * enviar y lo manda en POST /api/public/chats. Tiene que quedar en
 * {@code lead.phone} desde la creación, sin depender de que el agente lo
 * extraiga del texto (el vecino manda un solo mensaje y no vuelve, lead #277).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatStartPhoneTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;

  @Test
  void elTelefonoDelPostChatsQuedaEnLeadPhone() throws Exception {
    MvcResult res = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"channel\":\"web-chat\",\"phone\":\"099123456\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    Integer id = JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    assertThat(leadRepository.findById(Long.valueOf(id)).orElseThrow().getPhone()).isEqualTo("099123456");
  }
}
