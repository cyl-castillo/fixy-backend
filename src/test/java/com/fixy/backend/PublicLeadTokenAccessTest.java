package com.fixy.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Fix IDOR (market-readiness review 2026-07-13): los endpoints públicos
 * por-lead (GET /leads/{id}, /timeline, PATCH /context, POST /matches) eran
 * accesibles sin token — con IDs secuenciales, cualquiera podía leer PII
 * (nombre/teléfono/ubicación) y el accessToken de todos los leads, o mutar
 * leads ajenos. Ahora exigen el accessToken del lead como query param
 * (mismo patrón que /messages, /photos y /confirm-completion).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicLeadTokenAccessTest {

  @Autowired
  private MockMvc mockMvc;

  private record CreatedLead(Integer id, String token) {}

  private CreatedLead createLead() throws Exception {
    String payload = """
        {
          "name": "Cliente Privado",
          "phone": "093111222",
          "problem": "Se rompio un canio en la cocina",
          "channel": "web"
        }
        """;
    MvcResult result = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    return new CreatedLead(JsonPath.read(body, "$.id"), JsonPath.read(body, "$.accessToken"));
  }

  @Test
  void getLeadWithoutTokenRejected() throws Exception {
    CreatedLead lead = createLead();

    // Sin token: 400 (param requerido) — nunca el lead con PII.
    mockMvc.perform(get("/api/public/leads/{id}", lead.id()))
        .andExpect(status().isBadRequest());

    // Token ajeno/incorrecto: 403.
    mockMvc.perform(get("/api/public/leads/{id}", lead.id()).param("token", "token-robado"))
        .andExpect(status().isForbidden());

    // Token correcto: 200 con los datos del dueño.
    mockMvc.perform(get("/api/public/leads/{id}", lead.id()).param("token", lead.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phone").value("093111222"));
  }

  @Test
  void timelineWithoutTokenRejected() throws Exception {
    CreatedLead lead = createLead();

    mockMvc.perform(get("/api/public/leads/{id}/timeline", lead.id()))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/public/leads/{id}/timeline", lead.id()).param("token", "nope"))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/public/leads/{id}/timeline", lead.id()).param("token", lead.token()))
        .andExpect(status().isOk());
  }

  @Test
  void contextUpdateWithoutTokenRejected() throws Exception {
    CreatedLead lead = createLead();
    String mutation = """
        {"notes": "inyectado por un tercero"}
        """;

    mockMvc.perform(patch("/api/public/leads/{id}/context", lead.id())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mutation))
        .andExpect(status().isBadRequest());
    mockMvc.perform(patch("/api/public/leads/{id}/context", lead.id())
            .param("token", "nope")
            .contentType(MediaType.APPLICATION_JSON)
            .content(mutation))
        .andExpect(status().isForbidden());
    mockMvc.perform(patch("/api/public/leads/{id}/context", lead.id())
            .param("token", lead.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mutation))
        .andExpect(status().isOk());
  }

  @Test
  void matchesWithoutTokenRejected() throws Exception {
    CreatedLead lead = createLead();

    mockMvc.perform(post("/api/public/leads/{id}/matches", lead.id()))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/api/public/leads/{id}/matches", lead.id()).param("token", "nope"))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/public/leads/{id}/matches", lead.id()).param("token", lead.token()))
        .andExpect(status().isOk());
  }

  @Test
  void wrongTokenOnMissingLeadIs404NotLeak() throws Exception {
    // Lead inexistente: 404 limpio, sin stacktrace ni distinción explotable.
    mockMvc.perform(get("/api/public/leads/{id}", 999999).param("token", "x"))
        .andExpect(status().isNotFound());
  }
}
