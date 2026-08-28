package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
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

/**
 * Autoregistro público de comercios (Fase 1+2 "puerta única de registro",
 * Carlos 2026-08-27) — espejo de {@code ProviderRegistrationTest}: nace
 * ACTIVE con panelToken de una, login implícito si el sub de Google ya está
 * vinculado, 409 estructurado si el WhatsApp ya es de otro comercio.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BusinessRegistrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private BusinessRepository businessRepository;

  @MockitoBean
  private GoogleIdTokenVerifierService verifier;

  @MockitoBean
  private com.fixy.backend.service.TelegramNotifyService telegramNotifyService;

  private void mockIdentity(String credential, String sub, String email) {
    Mockito.when(verifier.isEnabled()).thenReturn(true);
    Mockito.when(verifier.verify(eq(credential)))
        .thenReturn(Optional.of(new GoogleIdTokenVerifierService.GoogleIdentity(sub, email, "Nuevo", null)));
  }

  private MvcResult register(String credential, String whatsapp) throws Exception {
    return register(credential, whatsapp, "panaderia", "Lagomar");
  }

  private MvcResult register(String credential, String whatsapp, String category, String zone) throws Exception {
    return mockMvc.perform(post("/api/public/businesses/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "credential": "%s",
                  "name": "Comercio Registro Test",
                  "whatsappNumber": "%s",
                  "category": "%s",
                  "zone": "%s"
                }
                """.formatted(credential, whatsapp, category, zone)))
        .andReturn();
  }

  @Test
  void registroFeliz_naceActivoConGoogleSubYPanelToken() throws Exception {
    mockIdentity("cred-biz-nuevo", "sub-biz-nuevo", "biznuevo@gmail.com");

    MvcResult res = register("cred-biz-nuevo", "098700001");
    assertThat(res.getResponse().getStatus()).isEqualTo(200);
    String body = res.getResponse().getContentAsString();
    Integer businessId = JsonPath.read(body, "$.businessId");
    String panelToken = JsonPath.read(body, "$.panelToken");
    Boolean alreadyExisted = JsonPath.read(body, "$.alreadyExisted");
    assertThat(panelToken).isNotBlank();
    assertThat(alreadyExisted).isFalse();

    Business saved = businessRepository.findById(Long.valueOf(businessId)).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(BusinessStatus.ACTIVE);
    assertThat(saved.getGoogleSub()).isEqualTo("sub-biz-nuevo");
    assertThat(saved.getGoogleEmail()).isEqualTo("biznuevo@gmail.com");
    assertThat(saved.getPanelToken()).isEqualTo(panelToken);
    assertThat(saved.getCategory()).isEqualTo("panaderia");

    Mockito.verify(telegramNotifyService).notifyBusinessSelfRegistered(
        Mockito.argThat(b -> "098700001".equals(b.getWhatsappNumber())));
  }

  @Test
  void subDeGoogleYaVinculado_esLoginImplicitoSinCrearNada() throws Exception {
    mockIdentity("cred-biz-repe", "sub-biz-repe", "bizrepe@gmail.com");

    MvcResult first = register("cred-biz-repe", "098700002");
    Integer firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.businessId");
    String firstToken = JsonPath.read(first.getResponse().getContentAsString(), "$.panelToken");

    long countBefore = businessRepository.count();
    // Segundo intento con el MISMO sub, datos de negocio distintos: se ignoran.
    MvcResult second = register("cred-biz-repe", "098700099", "kiosco", "Solymar");
    assertThat(second.getResponse().getStatus()).isEqualTo(200);
    String secondBody = second.getResponse().getContentAsString();
    assertThat((Integer) JsonPath.read(secondBody, "$.businessId")).isEqualTo(firstId);
    assertThat((String) JsonPath.read(secondBody, "$.panelToken")).isEqualTo(firstToken);
    assertThat((Boolean) JsonPath.read(secondBody, "$.alreadyExisted")).isTrue();
    assertThat(businessRepository.count()).isEqualTo(countBefore);
  }

  @Test
  void whatsappYaDeOtroComercio_es409ConCodePhoneInUseYAvisaAOps() throws Exception {
    mockIdentity("cred-biz-tel-a", "sub-biz-tel-a", "biztela@gmail.com");
    mockIdentity("cred-biz-tel-b", "sub-biz-tel-b", "biztelb@gmail.com");

    assertThat(register("cred-biz-tel-a", "098700003").getResponse().getStatus()).isEqualTo(200);

    MvcResult conflict = mockMvc.perform(post("/api/public/businesses/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "credential": "cred-biz-tel-b",
                  "name": "Otro Comercio",
                  "whatsappNumber": "098700003",
                  "category": "kiosco",
                  "zone": "Solymar"
                }
                """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("phone-in-use"))
        .andReturn();
    // Body PLANO {"code":"phone-in-use"} — no el sobre genérico {"error":{...}}.
    assertThat(conflict.getResponse().getContentAsString()).doesNotContain("\"error\"");

    Mockito.verify(telegramNotifyService).notifyExistingBusinessRegistrationAttempt(
        Mockito.argThat(b -> "098700003".equals(b.getWhatsappNumber())), Mockito.eq("biztelb@gmail.com"));
  }

  @Test
  void categoriaFueraDelCatalogo_es400() throws Exception {
    mockIdentity("cred-biz-cat", "sub-biz-cat", "bizcat@gmail.com");
    mockMvc.perform(post("/api/public/businesses/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "credential": "cred-biz-cat",
                  "name": "Comercio Categoria Invalida",
                  "whatsappNumber": "098700004",
                  "category": "categoria-que-no-existe",
                  "zone": "Solymar"
                }
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void credentialInvalido_es401() throws Exception {
    Mockito.when(verifier.isEnabled()).thenReturn(true);
    Mockito.when(verifier.verify(eq("cred-biz-falso"))).thenReturn(Optional.empty());

    mockMvc.perform(post("/api/public/businesses/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "credential": "cred-biz-falso",
                  "name": "Comercio X",
                  "whatsappNumber": "098700005",
                  "category": "kiosco",
                  "zone": "Solymar"
                }
                """))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void nombreCorto_es400() throws Exception {
    mockIdentity("cred-biz-nombre", "sub-biz-nombre", "biznombre@gmail.com");
    mockMvc.perform(post("/api/public/businesses/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "credential": "cred-biz-nombre",
                  "name": "X",
                  "whatsappNumber": "098700006",
                  "category": "kiosco",
                  "zone": "Solymar"
                }
                """))
        .andExpect(status().isBadRequest());
  }
}
