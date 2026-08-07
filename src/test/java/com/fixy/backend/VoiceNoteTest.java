package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.service.LeadMessageService;
import com.fixy.backend.service.LeadVoiceNoteService;
import com.fixy.backend.service.TranscriptionService;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Notas de voz (accesibilidad 2026-08: los mayores dictan, no tipean): el
 * audio entra al chat como mensaje del cliente con la transcripción como
 * texto y el audio adjunto; el agente responde el turno igual que si lo
 * hubiera tipeado. Si la transcripción falla, la nota no se pierde y Fixy
 * pide el reintento con un mensaje determinista.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
})
class VoiceNoteTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadMessageService leadMessageService;
  @MockitoBean private TranscriptionService transcriptionService;

  private record ChatSession(Integer leadId, String token) {}

  private ChatSession openChat() throws Exception {
    MvcResult chat = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"channel\":\"web-chat\"}"))
        .andExpect(status().is2xxSuccessful())
        .andReturn();
    Integer leadId = JsonPath.read(chat.getResponse().getContentAsString(), "$.id");
    String token = JsonPath.read(chat.getResponse().getContentAsString(), "$.accessToken");
    return new ChatSession(leadId, token);
  }

  private MockMultipartFile fakeAudio() {
    return new MockMultipartFile("file", "nota.webm", "audio/webm;codecs=opus", new byte[] {1, 2, 3, 4});
  }

  @Test
  void laNotaDeVozTranscriptaEntraAlChatYDisparaElTurnoDelAgente() throws Exception {
    when(transcriptionService.transcribe(any(), anyString(), anyString()))
        .thenReturn(Optional.of("Necesito plomería, tengo una pérdida de agua, estoy en Solymar"));

    ChatSession s = openChat();
    MvcResult res = mockMvc.perform(multipart("/api/public/leads/{id}/audio", s.leadId())
            .file(fakeAudio())
            .param("token", s.token()))
        .andExpect(status().isCreated())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.text"))
        .isEqualTo("Necesito plomería, tengo una pérdida de agua, estoy en Solymar");
    assertThat(JsonPath.<String>read(body, "$.audioUrl")).startsWith("/uploads/lead-").endsWith(".webm");
    assertThat(JsonPath.<String>read(body, "$.sender")).isEqualTo("customer");

    // La transcripción es EL mensaje del cliente: extracción + matching corren igual.
    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          var lead = leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow();
          assertThat(lead.getDetectedCategory()).isEqualTo("plomeria");
          assertThat(lead.getLocation()).isEqualTo("Solymar");
        });
  }

  @Test
  void siLaTranscripcionFallaLaNotaNoSePierdeYFixyPideReintento() throws Exception {
    when(transcriptionService.transcribe(any(), anyString(), anyString())).thenReturn(Optional.empty());

    ChatSession s = openChat();
    MvcResult res = mockMvc.perform(multipart("/api/public/leads/{id}/audio", s.leadId())
            .file(fakeAudio())
            .param("token", s.token()))
        .andExpect(status().isCreated())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.text")).isEqualTo(LeadVoiceNoteService.FALLBACK_TEXT);
    assertThat(JsonPath.<String>read(body, "$.audioUrl")).isNotBlank();

    // Fixy responde determinista pidiendo reintento — sin turno de LLM.
    boolean retryAsked = leadMessageService.recentForAgent(Long.valueOf(s.leadId()), 10).stream()
        .anyMatch(m -> "fixy".equals(m.getSender())
            && m.getText().equals(LeadVoiceNoteService.RETRY_REPLY));
    assertThat(retryAsked).isTrue();
  }

  @Test
  void tokenInvalidoRecibe403YNoGuardaNada() throws Exception {
    ChatSession s = openChat();
    mockMvc.perform(multipart("/api/public/leads/{id}/audio", s.leadId())
            .file(fakeAudio())
            .param("token", "token-falso"))
        .andExpect(status().isForbidden());
  }

  @Test
  void formatoNoAudioSeRechazaCon400() throws Exception {
    ChatSession s = openChat();
    MockMultipartFile exe = new MockMultipartFile("file", "malo.exe", "application/octet-stream", new byte[] {9});
    mockMvc.perform(multipart("/api/public/leads/{id}/audio", s.leadId())
            .file(exe)
            .param("token", s.token()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void elAudioUrlLlegaEnElListadoDeMensajesDelCliente() throws Exception {
    when(transcriptionService.transcribe(any(), anyString(), anyString()))
        .thenReturn(Optional.of("hola, quiero una torta para el sábado"));

    ChatSession s = openChat();
    mockMvc.perform(multipart("/api/public/leads/{id}/audio", s.leadId())
            .file(fakeAudio())
            .param("token", s.token()))
        .andExpect(status().isCreated());

    MvcResult list = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/public/leads/{id}/messages", s.leadId())
                .param("token", s.token()))
        .andExpect(status().isOk())
        .andReturn();
    String body = list.getResponse().getContentAsString();
    assertThat(body).contains("audioUrl");
    assertThat(JsonPath.<java.util.List<String>>read(body, "$[?(@.audioUrl)].text"))
        .contains("hola, quiero una torta para el sábado");
  }
}
