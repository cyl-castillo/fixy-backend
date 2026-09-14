package com.fixy.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /.well-known/fixy-agent.json} (contrato §8) — público, sin auth. */
@SpringBootTest
@AutoConfigureMockMvc
class WellKnownAgentControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void devuelveDescripcionConCategoriasZonasYUrls() throws Exception {
    mockMvc.perform(get("/.well-known/fixy-agent.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("fixy"))
        .andExpect(jsonPath("$.activeCategories[0]").exists())
        .andExpect(jsonPath("$.coverageZones[0]").exists())
        .andExpect(jsonPath("$.mcpUrl", org.hamcrest.Matchers.endsWith("/api/public/mcp")))
        .andExpect(jsonPath("$.catalogUrl", org.hamcrest.Matchers.endsWith("/api/public/catalog/services")))
        .andExpect(jsonPath("$.whatsappContact", org.hamcrest.Matchers.startsWith("https://wa.me/")));
  }
}
