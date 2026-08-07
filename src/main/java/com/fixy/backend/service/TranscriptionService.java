package com.fixy.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Transcribe notas de voz del cliente con la API de audio de OpenAI (misma
 * key que ya usa el agente de prod). Best-effort por diseño: si no hay key,
 * si la API falla o si el audio no se entiende, devuelve empty — el caller
 * decide el fallback (nunca se pierde la nota de voz por una transcripción
 * fallida).
 */
@Service
public class TranscriptionService {

  private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(40);

  private final WebClient webClient;
  private final ObjectMapper objectMapper;
  private final String apiKey;
  private final String model;

  public TranscriptionService(
      ObjectMapper objectMapper,
      @Value("${fixy.openai.api-key:}") String apiKey,
      @Value("${fixy.openai.transcribe-model:gpt-4o-mini-transcribe}") String model
  ) {
    this.objectMapper = objectMapper;
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.model = model;
    this.webClient = WebClient.builder()
        .baseUrl("https://api.openai.com/v1")
        // Audio de hasta ~10MB codificado en el body multipart.
        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
        .build();
  }

  public boolean isEnabled() {
    return !apiKey.isEmpty();
  }

  /**
   * Transcribe el audio a texto en español. Empty si el servicio está
   * apagado (sin key), la llamada falla o la transcripción viene vacía.
   */
  public Optional<String> transcribe(byte[] audioBytes, String filename, String contentType) {
    if (!isEnabled()) {
      return Optional.empty();
    }
    try {
      MultipartBodyBuilder body = new MultipartBodyBuilder();
      body.part("file", new ByteArrayResource(audioBytes) {
        @Override
        public String getFilename() {
          return filename;
        }
      }).contentType(MediaType.parseMediaType(contentType));
      body.part("model", model);
      body.part("language", "es");

      String raw = webClient.post()
          .uri("/audio/transcriptions")
          .header("Authorization", "Bearer " + apiKey)
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(BodyInserters.fromMultipartData(body.build()))
          .retrieve()
          .bodyToMono(String.class)
          .timeout(TIMEOUT)
          .block();

      if (raw == null || raw.isBlank()) {
        return Optional.empty();
      }
      JsonNode node = objectMapper.readTree(raw);
      String text = node.path("text").asText("").trim();
      return text.isEmpty() ? Optional.empty() : Optional.of(text);
    } catch (Exception ex) {
      log.warn("transcripción de nota de voz falló (se sigue con fallback): {}", ex.getMessage());
      return Optional.empty();
    }
  }
}
