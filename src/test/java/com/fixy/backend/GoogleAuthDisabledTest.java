package com.fixy.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Sin GOOGLE_CLIENT_ID configurado, el feature completo queda disabled:
 * los endpoints devuelven 503 con mensaje claro en vez de 500/NPE. Mismo
 * patrón que ProviderSelfServicePaymentsDisabledTest (contexto Spring
 * separado por distinta property, para no interferir con la suite general
 * que corre con el flag habilitado).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "fixy.auth.google-client-id=")
class GoogleAuthDisabledTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void loginReturns503WhenNotConfigured() throws Exception {
    mockMvc.perform(post("/api/public/auth/google")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cualquier-token\"}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void meLeadsReturns503WhenNotConfigured() throws Exception {
    mockMvc.perform(get("/api/public/me/leads")
            .header(HttpHeaders.AUTHORIZATION, "Bearer cualquier-cosa"))
        .andExpect(status().isServiceUnavailable());
  }
}
