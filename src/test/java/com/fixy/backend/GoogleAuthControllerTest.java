package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.repository.AppUserRepository;
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
 * Flujo completo Google Sign-In: login (verificador mockeado, sin pegarle
 * a Google real) -> upsert de AppUser -> vincular un lead anónimo por
 * accessToken -> listar "Mis pedidos". El GoogleIdTokenVerifierService real
 * ya tiene su propia suite unitaria (GoogleIdTokenVerifierServiceTest) que
 * cubre firma/audience/issuer/expiración contra JWKS de test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GoogleAuthControllerTest {

  private static final String FAKE_CREDENTIAL = "fake-id-token";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AppUserRepository appUserRepository;

  @MockitoBean
  private GoogleIdTokenVerifierService googleIdTokenVerifierService;

  @Test
  void loginUpsertsUserAndReusesSameUserOnSecondLogin() throws Exception {
    when(googleIdTokenVerifierService.isEnabled()).thenReturn(true);
    when(googleIdTokenVerifierService.verify(eq(FAKE_CREDENTIAL)))
        .thenReturn(Optional.of(new GoogleIdTokenVerifierService.GoogleIdentity(
            "google-sub-abc", "sofia@example.com", "Sofia Cliente", "https://example.com/pic.jpg")));

    MvcResult first = mockMvc.perform(post("/api/public/auth/google")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"%s\"}".formatted(FAKE_CREDENTIAL)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.email").value("sofia@example.com"))
        .andExpect(jsonPath("$.user.name").value("Sofia Cliente"))
        .andReturn();
    String firstToken = JsonPath.read(first.getResponse().getContentAsString(), "$.sessionToken");
    assertThat(firstToken).isNotBlank();

    long usersAfterFirstLogin = appUserRepository.count();

    MvcResult second = mockMvc.perform(post("/api/public/auth/google")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"%s\"}".formatted(FAKE_CREDENTIAL)))
        .andExpect(status().isOk())
        .andReturn();
    String secondToken = JsonPath.read(second.getResponse().getContentAsString(), "$.sessionToken");
    assertThat(secondToken).isNotBlank();

    // Mismo sub -> mismo AppUser, no se duplica.
    assertThat(appUserRepository.count()).isEqualTo(usersAfterFirstLogin);
  }

  @Test
  void invalidCredentialReturns401() throws Exception {
    when(googleIdTokenVerifierService.isEnabled()).thenReturn(true);
    when(googleIdTokenVerifierService.verify(eq("token-invalido")))
        .thenReturn(Optional.empty());

    mockMvc.perform(post("/api/public/auth/google")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"token-invalido\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void linkLeadWithMatchingAccessTokenIsIdempotentAndListable() throws Exception {
    when(googleIdTokenVerifierService.isEnabled()).thenReturn(true);
    when(googleIdTokenVerifierService.verify(eq(FAKE_CREDENTIAL)))
        .thenReturn(Optional.of(new GoogleIdTokenVerifierService.GoogleIdentity(
            "google-sub-link", "carlos@example.com", "Carlos", null)));

    MvcResult login = mockMvc.perform(post("/api/public/auth/google")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"%s\"}".formatted(FAKE_CREDENTIAL)))
        .andExpect(status().isOk())
        .andReturn();
    String sessionToken = JsonPath.read(login.getResponse().getContentAsString(), "$.sessionToken");

    MvcResult leadRes = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099123456",
                  "problem": "Perdida de agua en la cocina, necesito plomero",
                  "channel": "web-app"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.id");
    String accessToken = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.accessToken");

    String linkPayload = "{\"leadId\": %d, \"accessToken\": \"%s\"}".formatted(leadId, accessToken);

    // primera vinculacion
    mockMvc.perform(post("/api/public/me/leads")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(linkPayload))
        .andExpect(status().isOk());

    // idempotente: repetir no rompe ni duplica
    mockMvc.perform(post("/api/public/me/leads")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(linkPayload))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/public/me/leads")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(leadId));
  }

  @Test
  void linkLeadWithWrongAccessTokenReturns403() throws Exception {
    when(googleIdTokenVerifierService.isEnabled()).thenReturn(true);
    when(googleIdTokenVerifierService.verify(eq(FAKE_CREDENTIAL)))
        .thenReturn(Optional.of(new GoogleIdTokenVerifierService.GoogleIdentity(
            "google-sub-wrong-token", "otro@example.com", "Otro", null)));

    MvcResult login = mockMvc.perform(post("/api/public/auth/google")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"%s\"}".formatted(FAKE_CREDENTIAL)))
        .andExpect(status().isOk())
        .andReturn();
    String sessionToken = JsonPath.read(login.getResponse().getContentAsString(), "$.sessionToken");

    MvcResult leadRes = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099654321",
                  "problem": "Necesito electricista, se corto la luz",
                  "channel": "web-app"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(post("/api/public/me/leads")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"leadId\": %d, \"accessToken\": \"token-que-no-matchea\"}".formatted(leadId)))
        .andExpect(status().isForbidden());
  }

  @Test
  void meLeadsWithoutBearerReturns401() throws Exception {
    when(googleIdTokenVerifierService.isEnabled()).thenReturn(true);

    mockMvc.perform(get("/api/public/me/leads"))
        .andExpect(status().isUnauthorized());
  }
}
