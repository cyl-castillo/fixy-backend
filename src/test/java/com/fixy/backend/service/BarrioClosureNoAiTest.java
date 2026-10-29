package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Sin key de OpenAI: el cierre queda SIN_IA / DEBIL y el aviso a Telegram sale enseguida. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.uploads.dir=target/barrio-closure-noai-uploads",
    "fixy.barrio.ai-review.scheduler-initial-delay-ms=3600000",
    "fixy.barrio.ai-review.scheduler-fixed-delay-ms=3600000",
    "fixy.openai.api-key="
})
class BarrioClosureNoAiTest {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper mapper;
  @MockitoBean private TelegramNotifyService telegram;

  @Test
  void sinKeyElCierreAvisaEnseguidaConPruebasDebiles() throws Exception {
    var res = mvc.perform(multipart("/api/public/barrio/reports")
        .file(new MockMultipartFile("photo", "f.jpg", "image/jpeg", BarrioTestImages.textured(101)))
        .param("category", "bache").param("lat", "-34.7711").param("lng", "-55.9101")).andReturn();
    long id = mapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    mvc.perform(post("/api/ops/barrio/reports/{id}/approve", id).with(httpBasic("test-ops", "test-pass"))).andReturn();

    mvc.perform(multipart("/api/public/barrio/reports/{id}/closure", id)
        .file(new MockMultipartFile("photo", "c.jpg", "image/jpeg", BarrioTestImages.textured(102)))
        .param("lat", "-34.7711").param("lng", "-55.9101")).andReturn();

    verify(telegram, timeout(2000)).notifyBarrioClosure(eq(id), anyString(), anyString(), any(),
        contains("Pruebas: débiles — sin IA"));
    JsonNode closures = mapper.readTree(mvc.perform(get("/api/ops/barrio/pending").with(httpBasic("test-ops", "test-pass")))
        .andReturn().getResponse().getContentAsString()).get("closures");
    JsonNode mine = null;
    for (JsonNode n : closures) if (n.get("reportId").asLong() == id) mine = n;
    assertThat(mine).isNotNull();
    assertThat(mine.get("evidence").get("verdict").asText()).isEqualTo("DEBIL");
    assertThat(mine.get("evidence").get("ai").get("verdict").asText()).isEqualTo("SIN_IA");
  }
}
