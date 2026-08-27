package com.fixy.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Sin GOOGLE_CLIENT_ID configurado, el autoregistro público de comercio
 * queda disabled: 503 distinguible de "credential inválido" — mismo patrón
 * que {@code BusinessGoogleAuthDisabledTest} (contexto Spring separado por
 * distinta property, para no interferir con la suite general que corre con
 * el flag habilitado).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "fixy.auth.google-client-id=")
class BusinessRegistrationDisabledTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void registerReturns503WhenGoogleNotConfigured() throws Exception {
    mockMvc.perform(post("/api/public/businesses/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "credential": "cualquier-token",
                  "name": "Comercio Disabled Test",
                  "whatsappNumber": "098700007",
                  "category": "kiosco",
                  "zone": "Solymar"
                }
                """))
        .andExpect(status().isServiceUnavailable());
  }
}
