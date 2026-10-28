package com.fixy.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Superficie de Avisá (avisa.fixy.com.uy): CORS del origen nuevo (los links OG se cubren en BarrioApiTest). */
@SpringBootTest
@AutoConfigureMockMvc
class BarrioAvisaSurfaceTest {

  @Autowired private MockMvc mvc;

  @Test
  void preflightDesdeAvisaEsPermitido() throws Exception {
    mvc.perform(options("/api/public/barrio/reports")
            .header("Origin", "https://avisa.fixy.com.uy")
            .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "https://avisa.fixy.com.uy"));
  }

  @Test
  void preflightDeOrigenAjenoSigueRechazado() throws Exception {
    mvc.perform(options("/api/public/barrio/reports")
            .header("Origin", "https://otro.example.com")
            .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }
}
