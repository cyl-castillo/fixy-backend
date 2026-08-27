package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.repository.BusinessRepository;
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
 * {@code GET /api/public/me/merchant} (Fase 3 del panel del dueño,
 * 2026-08-27): descubre desde la sesión del chat (login de cliente,
 * {@code /api/public/auth/google}) si el googleSub del AppUser logueado
 * también está vinculado a un comercio ({@code Business.googleSub},
 * vinculado en Fase 1 vía el link mágico del panel). El verificador de
 * Google se mockea — acá se prueba el flujo de descubrimiento, no la
 * criptografía. Cada test usa su propio sub/comercio (misma cautela de
 * aislamiento que {@code BusinessGoogleAuthTest}: H2 compartida entre
 * contextos de Spring).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicMeMerchantTest {

  private static final String FAKE_CREDENTIAL = "fake-id-token-merchant";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private BusinessRepository businessRepository;

  @MockitoBean
  private GoogleIdTokenVerifierService verifier;

  private String loginAndGetSessionToken(String sub, String email) throws Exception {
    when(verifier.isEnabled()).thenReturn(true);
    when(verifier.verify(eq(FAKE_CREDENTIAL)))
        .thenReturn(Optional.of(new GoogleIdTokenVerifierService.GoogleIdentity(sub, email, "Dueña", null)));

    MvcResult login = mockMvc.perform(post("/api/public/auth/google")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"%s\"}".formatted(FAKE_CREDENTIAL)))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(login.getResponse().getContentAsString(), "$.sessionToken");
  }

  private Business persistBusinessLinkedTo(String tag, String googleSub, String panelToken) {
    Business business = new Business();
    business.setName("Comercio Dueña Test " + tag);
    business.setWhatsappNumber("0966" + tag);
    business.setCategory("otro");
    business.setPrimaryZone("Solymar");
    business.setStatus(BusinessStatus.ACTIVE);
    business.setGoogleSub(googleSub);
    business.setPanelToken(panelToken);
    return businessRepository.save(business);
  }

  @Test
  void cuentaVinculadaSinPanelTokenPrevio_generaUnoLazyYLoDevuelve() throws Exception {
    String sessionToken = loginAndGetSessionToken("sub-dueña-lazy", "duena-lazy@gmail.com");
    Business business = persistBusinessLinkedTo("010", "sub-dueña-lazy", null);
    assertThat(business.getPanelToken()).isNull();

    MvcResult result = mockMvc.perform(get("/api/public/me/merchant")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.businessId").value(business.getId()))
        .andExpect(jsonPath("$.name").value(business.getName()))
        .andReturn();

    String panelToken = JsonPath.read(result.getResponse().getContentAsString(), "$.panelToken");
    assertThat(panelToken).isNotBlank();

    Business reloaded = businessRepository.findById(business.getId()).orElseThrow();
    assertThat(reloaded.getPanelToken()).isEqualTo(panelToken);
  }

  @Test
  void cuentaVinculadaConPanelTokenExistente_loDevuelveSinRotarloEntreLlamadas() throws Exception {
    String sessionToken = loginAndGetSessionToken("sub-dueña-estable", "duena-estable@gmail.com");
    Business business = persistBusinessLinkedTo("011", "sub-dueña-estable", "panel-token-ya-compartido");

    MvcResult first = mockMvc.perform(get("/api/public/me/merchant")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken))
        .andExpect(status().isOk())
        .andReturn();
    MvcResult second = mockMvc.perform(get("/api/public/me/merchant")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken))
        .andExpect(status().isOk())
        .andReturn();

    String tokenFirst = JsonPath.read(first.getResponse().getContentAsString(), "$.panelToken");
    String tokenSecond = JsonPath.read(second.getResponse().getContentAsString(), "$.panelToken");
    assertThat(tokenFirst).isEqualTo(business.getPanelToken());
    assertThat(tokenSecond).isEqualTo(business.getPanelToken());
  }

  @Test
  void cuentaSinComercioVinculado_es404() throws Exception {
    String sessionToken = loginAndGetSessionToken("sub-sin-comercio", "sin-comercio@gmail.com");

    mockMvc.perform(get("/api/public/me/merchant")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void sinBearer_es401() throws Exception {
    when(verifier.isEnabled()).thenReturn(true);

    mockMvc.perform(get("/api/public/me/merchant"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void bearerInvalido_es401() throws Exception {
    when(verifier.isEnabled()).thenReturn(true);

    mockMvc.perform(get("/api/public/me/merchant")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token-manipulado"))
        .andExpect(status().isUnauthorized());
  }
}
