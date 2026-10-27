package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Descarga de media por Graph API: GET /{version}/{media-id} → {url, mime_type} → GET url con el mismo token. */
class WhatsAppServiceDownloadMediaTest {

  private HttpServer server;
  private final List<String> requests = new ArrayList<>();
  private byte[] fileBytes;

  @BeforeEach
  void start() throws Exception {
    fileBytes = new byte[600 * 1024]; // más que el buffer por defecto de WebClient (256 KB)
    for (int i = 0; i < fileBytes.length; i++) fileBytes[i] = (byte) (i % 251);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = server.getAddress().getPort();
    server.createContext("/v21.0/MEDIA123", ex -> {
      requests.add(ex.getRequestURI().getPath() + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
      byte[] body = ("{\"messaging_product\":\"whatsapp\",\"url\":\"http://127.0.0.1:" + port
          + "/lookaside/file.jpg\",\"mime_type\":\"image/jpeg\",\"id\":\"MEDIA123\"}").getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    server.createContext("/lookaside/file.jpg", ex -> {
      requests.add(ex.getRequestURI().getPath() + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
      ex.sendResponseHeaders(200, fileBytes.length);
      ex.getResponseBody().write(fileBytes);
      ex.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private WhatsAppService service(String token) {
    return new WhatsAppService(new ObjectMapper(), "PNID", token, "v21.0",
        "http://127.0.0.1:" + server.getAddress().getPort(), "bearer", "");
  }

  @Test
  void bajaElMediaEnDosPasosConElMismoToken() {
    WhatsAppService.MediaDownload media = service("TOKEN-X").downloadMedia("MEDIA123");

    assertThat(media).isNotNull();
    assertThat(media.mimeType()).isEqualTo("image/jpeg");
    assertThat(media.bytes()).isEqualTo(fileBytes);
    assertThat(requests).containsExactly(
        "/v21.0/MEDIA123 auth=Bearer TOKEN-X",
        "/lookaside/file.jpg auth=Bearer TOKEN-X");
  }

  @Test
  void devuelveNullSinCredencialesConIdRaroOSiMetaFalla() {
    assertThat(service("").downloadMedia("MEDIA123")).isNull();                // WhatsApp deshabilitado
    assertThat(service("T").downloadMedia("../etc/passwd")).isNull();          // id no válido: ni se pide
    assertThat(service("T").downloadMedia("NOEXISTE")).isNull();               // 404 de Meta
    assertThat(requests).isEmpty();
  }
}
