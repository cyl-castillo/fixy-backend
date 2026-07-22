package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.ProviderRepository;
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
 * Autoregistro de proveedores con aprobación: nace INACTIVE (sin bandeja,
 * sin accept, fuera del matching), entra igual a su panel con las
 * credenciales devueltas, y al aprobarlo en el admin (status AVAILABLE, el
 * botón Activar existente) la bandeja se enciende.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderRegistrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ProviderRepository providerRepository;

  @MockitoBean
  private GoogleIdTokenVerifierService verifier;

  private void mockIdentity(String credential, String sub, String email) {
    Mockito.when(verifier.verify(eq(credential)))
        .thenReturn(Optional.of(new GoogleIdTokenVerifierService.GoogleIdentity(sub, email, "Nuevo", null)));
  }

  private MvcResult register(String credential, String phone) throws Exception {
    return register(credential, phone, "[\"electricidad\", \"reparaciones\"]", "Lagomar");
  }

  private MvcResult register(String credential, String phone, String categoriesJson, String zone) throws Exception {
    return mockMvc.perform(post("/api/public/providers/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "credential": "%s",
                  "name": "Proveedor Registro Test",
                  "phone": "%s",
                  "categories": %s,
                  "primaryZone": "%s",
                  "coverageZones": "%s"
                }
                """.formatted(credential, phone, categoriesJson, zone, zone)))
        .andReturn();
  }

  @Test
  void registroFeliz_naceInactivoConCredencialesYLoginGoogleFunciona() throws Exception {
    mockIdentity("cred-nuevo", "sub-nuevo", "nuevo@gmail.com");

    MvcResult res = register("cred-nuevo", "099740001");
    assertThat(res.getResponse().getStatus()).isEqualTo(201);
    Integer providerId = JsonPath.read(res.getResponse().getContentAsString(), "$.providerId");
    String token = JsonPath.read(res.getResponse().getContentAsString(), "$.accessToken");
    assertThat(token).isNotBlank();

    Provider saved = providerRepository.findById(Long.valueOf(providerId)).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(ProviderStatus.INACTIVE);
    assertThat(saved.getSourceType()).isEqualTo("autoregistro");
    assertThat(saved.getGoogleEmail()).isEqualTo("nuevo@gmail.com");
    assertThat(saved.getCategories()).isEqualTo("electricidad,reparaciones");

    // Puede entrar con Google desde ya (mismas credenciales del panel).
    mockMvc.perform(post("/api/public/auth/google-provider")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cred-nuevo\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.providerId").value(providerId))
        .andExpect(jsonPath("$.accessToken").value(token));

    // El panel abre (200 en /me) pero la bandeja está vacía y el accept 403.
    mockMvc.perform(get("/api/public/providers/{id}/me", providerId).param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("INACTIVE"));
    mockMvc.perform(get("/api/public/providers/{id}/opportunities", providerId).param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    mockMvc.perform(post("/api/public/providers/{id}/opportunities/{leadId}/accept", providerId, 99999)
            .param("token", token))
        .andExpect(status().isForbidden());
  }

  @Test
  void aprobarloEnElAdmin_enciendeLaBandeja() throws Exception {
    // Combinación categoría+zona única en la suite (pastelería/Parque Miramar):
    // evita que el auto-match le asigne el lead a un proveedor de otro test
    // (la suite comparte base) y lo saque del broadcast antes del assert.
    mockIdentity("cred-aprobar", "sub-aprobar", "aprobar@gmail.com");
    MvcResult res = register("cred-aprobar", "099740002", "[\"pasteleria\"]", "Parque Miramar");
    Integer providerId = JsonPath.read(res.getResponse().getContentAsString(), "$.providerId");
    String token = JsonPath.read(res.getResponse().getContentAsString(), "$.accessToken");

    // Lead matcheable (pastelería en Parque Miramar) que NO debe ver antes de
    // aprobar. El lead público no nace readyForMatching: hay que disparar
    // el matching (POST /matches), igual que el flujo real.
    MvcResult leadRes = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099740102",
                  "problem": "Quiero una torta grande, prueba registro",
                  "channel": "web-app",
                  "serviceCategory": "pasteleria",
                  "zone": "Parque Miramar"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.id");
    String leadToken = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.accessToken");
    mockMvc.perform(post("/api/public/leads/{id}/matches", leadId).param("token", leadToken))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/public/providers/{id}/opportunities", providerId).param("token", token))
        .andExpect(jsonPath("$").isEmpty());

    // Aprobación = el botón Activar del admin (PATCH status AVAILABLE).
    mockMvc.perform(patch("/api/providers/{id}", providerId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"AVAILABLE\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/public/providers/{id}/opportunities", providerId).param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.category=='pasteleria')]").isNotEmpty());
  }

  @Test
  void cuentaGoogleYaRegistrada_es409() throws Exception {
    mockIdentity("cred-repe", "sub-repe", "repe@gmail.com");
    assertThat(register("cred-repe", "099740003").getResponse().getStatus()).isEqualTo(201);
    assertThat(register("cred-repe", "099740004").getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void telefonoYaRegistrado_es409() throws Exception {
    mockIdentity("cred-tel-a", "sub-tel-a", "tela@gmail.com");
    mockIdentity("cred-tel-b", "sub-tel-b", "telb@gmail.com");
    assertThat(register("cred-tel-a", "099740005").getResponse().getStatus()).isEqualTo(201);
    assertThat(register("cred-tel-b", "099740005").getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void categoriaInvalidaOCredentialFalso_fallanClaro() throws Exception {
    mockIdentity("cred-cat", "sub-cat", "cat@gmail.com");
    mockMvc.perform(post("/api/public/providers/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "credential": "cred-cat",
                  "name": "X",
                  "phone": "099740006",
                  "categories": ["hackeria"],
                  "primaryZone": "Lagomar"
                }
                """))
        .andExpect(status().isBadRequest());

    Mockito.when(verifier.verify(eq("cred-falso"))).thenReturn(Optional.empty());
    mockMvc.perform(post("/api/public/providers/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "credential": "cred-falso",
                  "name": "X",
                  "phone": "099740007",
                  "categories": ["electricidad"],
                  "primaryZone": "Lagomar"
                }
                """))
        .andExpect(status().isUnauthorized());
  }
}
