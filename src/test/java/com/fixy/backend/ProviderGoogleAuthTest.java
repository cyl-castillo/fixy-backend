package com.fixy.backend;

import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.service.GoogleIdTokenVerifierService;
import com.jayway.jsonpath.JsonPath;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Google Sign-In del proveedor: vincular con el panel abierto por link
 * mágico (posesión probada) y después entrar desde cualquier teléfono con
 * la cuenta de Google. El verificador de Google se mockea — acá se prueba
 * el flujo, no la criptografía de Google.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderGoogleAuthTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private GoogleIdTokenVerifierService verifier;

  private record ProviderCtx(Integer id, String token) {
  }

  private ProviderCtx createProvider(String phone) throws Exception {
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Proveedor Google Test %s",
                  "phone": "%s",
                  "primaryZone": "Solymar",
                  "city": "Ciudad de la Costa",
                  "categories": "plomeria"
                }
                """.formatted(phone, phone)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer providerId = JsonPath.read(prov.getResponse().getContentAsString(), "$.id");
    MvcResult tk = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String token = JsonPath.read(tk.getResponse().getContentAsString(), "$.accessToken");
    return new ProviderCtx(providerId, token);
  }

  private void mockIdentity(String credential, String sub, String email) {
    Mockito.when(verifier.verify(eq(credential)))
        .thenReturn(Optional.of(new GoogleIdTokenVerifierService.GoogleIdentity(sub, email, "Melissa", null)));
  }

  @Test
  void vincularYDespuesEntrarConGoogle_devuelveLasCredencialesDelPanel() throws Exception {
    ProviderCtx ctx = createProvider("099730001");
    mockIdentity("cred-melissa", "sub-melissa", "melissa@gmail.com");

    mockMvc.perform(post("/api/public/providers/{id}/link-google", ctx.id())
            .param("token", ctx.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-melissa\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.googleEmail").value("melissa@gmail.com"));

    // El /me ahora muestra la cuenta vinculada (para el estado del panel).
    mockMvc.perform(get("/api/public/providers/{id}/me", ctx.id()).param("token", ctx.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.googleEmail").value("melissa@gmail.com"));

    // Login: mismas credenciales del link mágico, sin rotarlas.
    mockMvc.perform(post("/api/public/auth/google-provider")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-melissa\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.providerId").value(ctx.id()))
        .andExpect(jsonPath("$.accessToken").value(ctx.token()));
  }

  @Test
  void loginSinCuentaVinculada_es404() throws Exception {
    mockIdentity("cred-desconocido", "sub-desconocido", "x@gmail.com");
    mockMvc.perform(post("/api/public/auth/google-provider")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-desconocido\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void vincularConTokenDePanelInvalido_es403() throws Exception {
    ProviderCtx ctx = createProvider("099730002");
    mockIdentity("cred-robo", "sub-robo", "robo@gmail.com");
    mockMvc.perform(post("/api/public/providers/{id}/link-google", ctx.id())
            .param("token", "token-robado")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-robo\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void mismaCuentaGoogleEnOtroProveedor_es409() throws Exception {
    ProviderCtx uno = createProvider("099730003");
    ProviderCtx dos = createProvider("099730004");
    mockIdentity("cred-compartida", "sub-compartida", "compartida@gmail.com");

    mockMvc.perform(post("/api/public/providers/{id}/link-google", uno.id())
            .param("token", uno.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-compartida\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/providers/{id}/link-google", dos.id())
            .param("token", dos.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-compartida\"}"))
        .andExpect(status().isConflict());

    // Re-vincular la MISMA cuenta al mismo proveedor es idempotente.
    mockMvc.perform(post("/api/public/providers/{id}/link-google", uno.id())
            .param("token", uno.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-compartida\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void credentialInvalido_es401() throws Exception {
    ProviderCtx ctx = createProvider("099730005");
    Mockito.when(verifier.verify(eq("cred-falso"))).thenReturn(Optional.empty());

    mockMvc.perform(post("/api/public/providers/{id}/link-google", ctx.id())
            .param("token", ctx.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-falso\"}"))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/api/public/auth/google-provider")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-falso\"}"))
        .andExpect(status().isUnauthorized());
  }
}
