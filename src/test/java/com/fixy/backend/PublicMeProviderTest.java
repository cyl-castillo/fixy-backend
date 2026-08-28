package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.GoogleIdTokenVerifierService;
import com.jayway.jsonpath.JsonPath;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/public/me/provider} (Fase 4 de la puerta única,
 * 2026-08-27, espejo exacto de {@link PublicMeMerchantTest}): descubre desde
 * la sesión del chat (login de cliente, {@code /api/public/auth/google}) si
 * el googleSub del AppUser logueado también está vinculado a un proveedor
 * ({@code Provider.googleSub}, vinculado vía
 * {@code /api/public/providers/{id}/link-google}). El verificador de Google
 * se mockea — acá se prueba el flujo de descubrimiento, no la criptografía.
 * Cada test usa su propio sub/proveedor (misma cautela de aislamiento que
 * {@code ProviderGoogleAuthTest}: H2 compartida entre contextos de Spring).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicMeProviderTest {

  private static final String FAKE_CREDENTIAL = "fake-id-token-provider";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ProviderRepository providerRepository;

  @MockitoBean
  private GoogleIdTokenVerifierService verifier;

  private String loginAndGetSessionToken(String sub, String email) throws Exception {
    when(verifier.isEnabled()).thenReturn(true);
    when(verifier.verify(eq(FAKE_CREDENTIAL)))
        .thenReturn(Optional.of(new GoogleIdTokenVerifierService.GoogleIdentity(sub, email, "Proveedor", null)));

    MvcResult login = mockMvc.perform(post("/api/public/auth/google")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"%s\"}".formatted(FAKE_CREDENTIAL)))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(login.getResponse().getContentAsString(), "$.sessionToken");
  }

  private Provider persistProviderLinkedTo(String tag, String googleSub, String accessToken) {
    Provider provider = new Provider();
    provider.setName("Proveedor Dueño Test " + tag);
    provider.setPhone("0977" + tag);
    provider.setCategories("plomeria");
    provider.setPrimaryZone("Solymar");
    provider.setGoogleSub(googleSub);
    provider.setAccessToken(accessToken);
    return providerRepository.save(provider);
  }

  @Test
  void cuentaVinculadaSinAccessTokenPrevio_generaUnoLazyYLoDevuelve() throws Exception {
    String sessionToken = loginAndGetSessionToken("sub-proveedor-lazy", "proveedor-lazy@gmail.com");
    Provider provider = persistProviderLinkedTo("010", "sub-proveedor-lazy", null);
    assertThat(provider.getAccessToken()).isNull();

    MvcResult result = mockMvc.perform(get("/api/public/me/provider")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.providerId").value(provider.getId()))
        .andExpect(jsonPath("$.name").value(provider.getName()))
        .andReturn();

    String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    assertThat(accessToken).isNotBlank();

    Provider reloaded = providerRepository.findById(provider.getId()).orElseThrow();
    assertThat(reloaded.getAccessToken()).isEqualTo(accessToken);
  }

  @Test
  void cuentaVinculadaConAccessTokenExistente_loDevuelveSinRotarloEntreLlamadas() throws Exception {
    String sessionToken = loginAndGetSessionToken("sub-proveedor-estable", "proveedor-estable@gmail.com");
    Provider provider = persistProviderLinkedTo("011", "sub-proveedor-estable", "access-token-ya-compartido");

    MvcResult first = mockMvc.perform(get("/api/public/me/provider")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken))
        .andExpect(status().isOk())
        .andReturn();
    MvcResult second = mockMvc.perform(get("/api/public/me/provider")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken))
        .andExpect(status().isOk())
        .andReturn();

    String tokenFirst = JsonPath.read(first.getResponse().getContentAsString(), "$.accessToken");
    String tokenSecond = JsonPath.read(second.getResponse().getContentAsString(), "$.accessToken");
    assertThat(tokenFirst).isEqualTo(provider.getAccessToken());
    assertThat(tokenSecond).isEqualTo(provider.getAccessToken());
  }

  @Test
  void cuentaSinProveedorVinculado_es404() throws Exception {
    String sessionToken = loginAndGetSessionToken("sub-sin-proveedor", "sin-proveedor@gmail.com");

    mockMvc.perform(get("/api/public/me/provider")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void sinBearer_es401() throws Exception {
    when(verifier.isEnabled()).thenReturn(true);

    mockMvc.perform(get("/api/public/me/provider"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void bearerInvalido_es401() throws Exception {
    when(verifier.isEnabled()).thenReturn(true);

    mockMvc.perform(get("/api/public/me/provider")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token-manipulado"))
        .andExpect(status().isUnauthorized());
  }
}
