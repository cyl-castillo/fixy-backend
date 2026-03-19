package com.fixy.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LeadControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void shouldCreateListAndUpdateLead() throws Exception {
    String createPayload = """
        {
          "name": "Lucia",
          "phone": "093551242",
          "problem": "Se rompio la ducha y pierde agua sin parar en Montevideo",
          "channel": "whatsapp"
        }
        """;

    mockMvc.perform(post("/api/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("NEW"))
        .andExpect(jsonPath("$.detectedCategory").value("plomeria"));

    mockMvc.perform(get("/api/leads"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].phone").value("093551242"));

    String updatePayload = """
        {
          "status": "ASSIGNED",
          "assignedProvider": "Proveedor Demo"
        }
        """;

    mockMvc.perform(patch("/api/leads/1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(updatePayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.assignedProvider").value("Proveedor Demo"));
  }
}
