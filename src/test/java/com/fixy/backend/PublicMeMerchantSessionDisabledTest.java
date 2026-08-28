package com.fixy.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/public/me/merchant} y {@code GET /api/public/me/provider}
 * sin {@code AUTH_SESSION_SECRET} configurado (session tokens
 * deshabilitados): 503 en ambos, mismo patrón que el resto de
 * {@code /api/public/me/**} ({@code GoogleAuthDisabledTest}) — los dos
 * comparten el mismo {@code AuthService.requireUserEntity}, así que un solo
 * contexto Spring alcanza para cubrir ambos endpoints (evita levantar dos
 * contextos por la misma property). Deliberadamente distinto de
 * {@code GoogleAuthDisabledTest} (que apaga
 * {@code fixy.auth.google-client-id}): acá se apaga específicamente
 * {@code fixy.auth.session-secret} para aislar la rama de
 * {@code SessionTokenService.isEnabled() == false} dentro de
 * {@code AuthService.isEnabled()} — contexto Spring separado por distinta
 * property, para no interferir con la suite general.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "fixy.auth.session-secret=")
class PublicMeMerchantSessionDisabledTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void myMerchantReturns503WhenSessionSecretNotConfigured() throws Exception {
    mockMvc.perform(get("/api/public/me/merchant")
            .header(HttpHeaders.AUTHORIZATION, "Bearer cualquier-cosa"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void myProviderReturns503WhenSessionSecretNotConfigured() throws Exception {
    mockMvc.perform(get("/api/public/me/provider")
            .header(HttpHeaders.AUTHORIZATION, "Bearer cualquier-cosa"))
        .andExpect(status().isServiceUnavailable());
  }
}
