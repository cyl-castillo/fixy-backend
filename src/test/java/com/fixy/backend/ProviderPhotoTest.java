package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tier 2 (contrato §B.1): "Tu foto" del proveedor — POST/DELETE
 * {@code /api/public/providers/{id}/photo}, misma validación que las fotos
 * de lead (jpg/png/webp, tope propio de 5 MB).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderPhotoTest {

  @Autowired private MockMvc mockMvc;

  private Integer createProvider(String name, String phone) throws Exception {
    String payload = """
        {"name": "%s", "phone": "%s", "primaryZone": "Solymar", "city": "Ciudad de la Costa", "categories": "plomeria"}
        """.formatted(name, phone);
    MvcResult result = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private String accessTokenFor(Integer providerId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }

  @Test
  void subeYQuitaLaFotoDePerfil() throws Exception {
    Integer providerId = createProvider("Plomero Con Foto", "099300001");
    String token = accessTokenFor(providerId);

    MockMultipartFile file = new MockMultipartFile("file", "yo.jpg", "image/jpeg", new byte[] {1, 2, 3, 4});

    mockMvc.perform(multipart("/api/public/providers/{id}/photo", providerId)
            .file(file)
            .param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.photoUrl").exists())
        .andExpect(jsonPath("$.photoUrl").value(org.hamcrest.Matchers.endsWith(".jpg")));

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/public/providers/{id}/me", providerId).param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.photoUrl").exists());

    mockMvc.perform(delete("/api/public/providers/{id}/photo", providerId).param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.photoUrl").doesNotExist());
  }

  @Test
  void rechazaFormatoNoPermitido() throws Exception {
    Integer providerId = createProvider("Plomero Formato Malo", "099300002");
    String token = accessTokenFor(providerId);

    MockMultipartFile file = new MockMultipartFile("file", "yo.pdf", "application/pdf", new byte[] {1, 2, 3});

    mockMvc.perform(multipart("/api/public/providers/{id}/photo", providerId)
            .file(file)
            .param("token", token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rechazaArchivoDemasiadoGrande() throws Exception {
    Integer providerId = createProvider("Plomero Archivo Grande", "099300003");
    String token = accessTokenFor(providerId);

    byte[] tooLarge = new byte[6 * 1024 * 1024]; // 6MB > 5MB permitido
    MockMultipartFile file = new MockMultipartFile("file", "yo.jpg", "image/jpeg", tooLarge);

    mockMvc.perform(multipart("/api/public/providers/{id}/photo", providerId)
            .file(file)
            .param("token", token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rechazaTokenInvalido() throws Exception {
    Integer providerId = createProvider("Plomero Token Malo", "099300004");
    MockMultipartFile file = new MockMultipartFile("file", "yo.jpg", "image/jpeg", new byte[] {1, 2, 3});

    mockMvc.perform(multipart("/api/public/providers/{id}/photo", providerId)
            .file(file)
            .param("token", "wrong-token"))
        .andExpect(status().isForbidden());
  }
}
