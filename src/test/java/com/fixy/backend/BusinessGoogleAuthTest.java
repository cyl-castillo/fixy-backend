package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Google Sign-In del DUEÑO DEL COMERCIO (Fase 1, 2026-08-27) — espejo de
 * {@code ProviderGoogleAuthTest}: vincular con el panel abierto por link
 * mágico (posesión probada) y después entrar desde cualquier teléfono con
 * la cuenta de Google, sin rotar el panelToken ya compartido por WhatsApp.
 * El verificador de Google se mockea — acá se prueba el flujo, no la
 * criptografía de Google. Cada test usa su propio comercio (mismo criterio
 * de aislamiento que {@code MerchantPanelSurfaceTest}: H2 compartida entre
 * contextos de Spring).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BusinessGoogleAuthTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private BusinessRepository businessRepository;

  @MockitoBean
  private GoogleIdTokenVerifierService verifier;

  private Business persistBusiness(String tag) {
    Business business = new Business();
    business.setName("Comercio Google Test " + tag);
    business.setWhatsappNumber("0977" + tag);
    business.setCategory("otro");
    business.setPrimaryZone("Solymar");
    business.setStatus(BusinessStatus.ACTIVE);
    business.setPanelToken("panel-google-token-" + tag);
    return businessRepository.save(business);
  }

  private void mockIdentity(String credential, String sub, String email) {
    Mockito.when(verifier.isEnabled()).thenReturn(true);
    Mockito.when(verifier.verify(eq(credential)))
        .thenReturn(Optional.of(new GoogleIdTokenVerifierService.GoogleIdentity(sub, email, "Melissa", null)));
  }

  @Test
  void vincularYDespuesEntrarConGoogle_devuelveElPanelTokenExistenteSinRotarlo() throws Exception {
    Business business = persistBusiness("001");
    mockIdentity("cred-melissa", "sub-melissa", "melissa@gmail.com");

    mockMvc.perform(post("/api/public/merchant/{token}/link-google", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-melissa\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.googleEmail").value("melissa@gmail.com"));

    // El GET del panel ahora muestra la cuenta vinculada (estado del panel).
    mockMvc.perform(get("/api/public/merchant/{token}", business.getPanelToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.business.googleEmail").value("melissa@gmail.com"));

    // Login: mismo panelToken del link mágico, SIN rotarlo.
    mockMvc.perform(post("/api/public/auth/google-business")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-melissa\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.businessId").value(business.getId()))
        .andExpect(jsonPath("$.name").value(business.getName()))
        .andExpect(jsonPath("$.panelToken").value(business.getPanelToken()));
  }

  @Test
  void getPanelSinVincularMuestraGoogleEmailNull() throws Exception {
    Business business = persistBusiness("008");
    MvcResult result = mockMvc.perform(get("/api/public/merchant/{token}", business.getPanelToken()))
        .andExpect(status().isOk())
        .andReturn();

    Object googleEmail = JsonPath.read(result.getResponse().getContentAsString(), "$.business.googleEmail");
    assertThat(googleEmail).isNull();
  }

  @Test
  void loginSinCuentaVinculada_es404() throws Exception {
    mockIdentity("cred-desconocido", "sub-desconocido", "x@gmail.com");
    mockMvc.perform(post("/api/public/auth/google-business")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-desconocido\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void vincularConTokenDePanelInvalido_es404Opaco() throws Exception {
    mockIdentity("cred-robo", "sub-robo", "robo@gmail.com");
    mockMvc.perform(post("/api/public/merchant/{token}/link-google", "token-robado")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-robo\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void mismaCuentaGoogleEnOtroComercio_es409() throws Exception {
    Business uno = persistBusiness("003");
    Business dos = persistBusiness("004");
    mockIdentity("cred-compartida", "sub-compartida", "compartida@gmail.com");

    mockMvc.perform(post("/api/public/merchant/{token}/link-google", uno.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-compartida\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/merchant/{token}/link-google", dos.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-compartida\"}"))
        .andExpect(status().isConflict());

    // Re-vincular la MISMA cuenta al mismo comercio es idempotente.
    mockMvc.perform(post("/api/public/merchant/{token}/link-google", uno.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-compartida\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void reVincularElMismoComercioConOtraCuentaEstaPermitido() throws Exception {
    Business business = persistBusiness("005");
    mockIdentity("cred-vieja", "sub-vieja", "vieja@gmail.com");

    mockMvc.perform(post("/api/public/merchant/{token}/link-google", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-vieja\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.googleEmail").value("vieja@gmail.com"));

    mockIdentity("cred-nueva", "sub-nueva", "nueva@gmail.com");
    // La posesión del link del panel manda: se puede re-vincular con otra cuenta.
    mockMvc.perform(post("/api/public/merchant/{token}/link-google", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-nueva\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.googleEmail").value("nueva@gmail.com"));

    // Login ahora resuelve por la cuenta NUEVA; la vieja ya no está vinculada a este comercio.
    mockMvc.perform(post("/api/public/auth/google-business")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-vieja\"}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/public/auth/google-business")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-nueva\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.businessId").value(business.getId()));
  }

  @Test
  void credentialInvalido_es401() throws Exception {
    Business business = persistBusiness("006");
    Mockito.when(verifier.isEnabled()).thenReturn(true);
    Mockito.when(verifier.verify(eq("cred-falso"))).thenReturn(Optional.empty());

    mockMvc.perform(post("/api/public/merchant/{token}/link-google", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-falso\"}"))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/api/public/auth/google-business")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-falso\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void loginNoRotaElPanelTokenEnLoginsSucesivos() throws Exception {
    Business business = persistBusiness("007");
    mockIdentity("cred-repetida", "sub-repetida", "repetida@gmail.com");

    mockMvc.perform(post("/api/public/merchant/{token}/link-google", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-repetida\"}"))
        .andExpect(status().isOk());

    MvcResult first = mockMvc.perform(post("/api/public/auth/google-business")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-repetida\"}"))
        .andExpect(status().isOk())
        .andReturn();
    MvcResult second = mockMvc.perform(post("/api/public/auth/google-business")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-repetida\"}"))
        .andExpect(status().isOk())
        .andReturn();

    String tokenFirst = JsonPath.read(first.getResponse().getContentAsString(), "$.panelToken");
    String tokenSecond = JsonPath.read(second.getResponse().getContentAsString(), "$.panelToken");
    assertThat(tokenFirst).isEqualTo(business.getPanelToken());
    assertThat(tokenSecond).isEqualTo(business.getPanelToken());
  }
}
