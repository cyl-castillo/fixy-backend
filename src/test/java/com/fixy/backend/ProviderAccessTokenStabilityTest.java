package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.ProviderRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Incidente 2026-07-27 (Carnot Clima): "copiar link" del admin rotaba el
 * token en CADA toque, invalidando el link que el proveedor ya tenía
 * guardado en WhatsApp. Copiar debe ser de solo lectura; rotar es una
 * decisión explícita (?rotate=true).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderAccessTokenStabilityTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ProviderRepository providerRepository;

  private Long createProvider() {
    Provider provider = new Provider();
    provider.setName("Proveedor Token Estable");
    provider.setPhone("099777333");
    provider.setCategories("plomeria");
    return providerRepository.save(provider).getId();
  }

  private String requestToken(Long id, String query) throws Exception {
    String body = mockMvc.perform(post("/api/providers/{id}/access-token" + query, id)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.accessToken");
  }

  @Test
  void copiarElLinkNoRotaElTokenExistente() throws Exception {
    Long id = createProvider();
    String first = requestToken(id, "");
    String second = requestToken(id, "");
    assertThat(second)
        .as("dos copias seguidas devuelven el MISMO link — copiar no invalida lo compartido")
        .isEqualTo(first);
  }

  @Test
  void rotarExplicitoSiCambiaElToken() throws Exception {
    Long id = createProvider();
    String first = requestToken(id, "");
    String rotated = requestToken(id, "?rotate=true");
    assertThat(rotated).isNotEqualTo(first);
    // Y después de rotar, copiar devuelve el nuevo de forma estable.
    assertThat(requestToken(id, "")).isEqualTo(rotated);
  }
}
