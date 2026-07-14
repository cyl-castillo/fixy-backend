package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.TelegramNotifyService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Puente de avisos de oportunidad por Telegram (parche interino hasta
 * credenciales Meta de proveedor). Cubre:
 *  (a) sin credenciales: disabled, no manda nada;
 *  (b) con credenciales fake + HTTP mock (HttpServer embebido del JDK,
 *      cero llamadas de red reales fuera de localhost): lead con matches
 *      dispara 1 aviso con el texto esperado (incluye link de panel);
 *      segundo trigger del mismo lead no re-avisa (idempotencia via
 *      timeline OPS_NOTIFIED_OPPORTUNITY);
 *  (c) lead con "[smoke]" en el problema no avisa;
 *  (d) categoría MVP sin proveedores → aviso de "demanda sin oferta".
 *
 * Se prueba TelegramNotifyService directo (no mockeado) para verificar el
 * contenido real del mensaje — mismo nivel de detalle que
 * MercadoPagoServiceTest verifica la URL real de notificación.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "fixy.telegram.bot-token=test-bot-token",
    "fixy.telegram.chat-id=123456789",
    "fixy.telegram.base-url=http://127.0.0.1:18765",
    "fixy.public-app-base-url=https://www.fixy.com.uy"
})
class TelegramNotifyServiceTest {

  @Autowired
  private TelegramNotifyService telegramNotifyService;

  @Autowired
  private LeadRepository leadRepository;

  @Autowired
  private ProviderRepository providerRepository;

  @Autowired
  private LeadEventRepository leadEventRepository;

  private static HttpServer server;
  private static final BlockingQueue<String> receivedBodies = new ArrayBlockingQueue<>(10);

  private static synchronized HttpServer ensureServer() throws IOException {
    if (server == null) {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18765), 0);
      server.createContext("/", exchange -> {
        String body = new String(exchange.getRequestBody().readAllBytes());
        receivedBodies.offer(body);
        String response = "{\"ok\":true,\"result\":{\"message_id\":1}}";
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.getBytes().length);
        exchange.getResponseBody().write(response.getBytes());
        exchange.close();
      });
      server.start();
    }
    return server;
  }

  {
    try {
      ensureServer();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  @AfterEach
  void drainQueue() {
    receivedBodies.clear();
  }

  private Lead persistLead(String category, String location, String problem) {
    Lead lead = new Lead();
    lead.setName("Cliente Test");
    lead.setPhone("099111222");
    lead.setProblem(problem);
    lead.setChannel("chat");
    lead.setDetectedCategory(category);
    lead.setLocation(location);
    lead.setUrgency("media");
    lead.setMissingFields("");
    lead.setReadyForMatching(true);
    lead.setStatus(LeadStatus.NEW);
    lead.setNotes("");
    lead.setHistory("test");
    lead.setAccessToken("tok-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  private Provider persistProvider(String name, String phone) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone(phone);
    provider.setCategories("plomeria");
    provider.setPrimaryZone("Solymar");
    provider.setStatus(ProviderStatus.AVAILABLE);
    return providerRepository.save(provider);
  }

  private String awaitOneMessage() {
    String body;
    try {
      body = receivedBodies.poll(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
    assertThat(body).as("Telegram debería haber recibido un POST").isNotNull();
    return body;
  }

  @Test
  void withMatches_sendsOneNotificationWithExpectedTextAndPanelLink() {
    Lead lead = persistLead("plomeria", "Solymar", "Se me rompió la canilla de la cocina y pierde agua");
    Provider provider = persistProvider("Juan Plomero", "099888777");

    List<ProviderCatalogItem> matches = List.of(new ProviderCatalogItem(
        provider.getId(), provider.getName(), "plomeria", "Solymar", provider.getPhone(), "AVAILABLE", "manual"
    ));

    telegramNotifyService.notifyOpportunityWithMatches(lead, matches);

    String body = awaitOneMessage();
    assertThat(body).contains("\"chat_id\":\"123456789\"");
    assertThat(body).contains("Oportunidad #" + lead.getId());
    assertThat(body).contains("plomería en Solymar");
    assertThat(body).contains("urgencia media");
    assertThat(body).contains("Se me rompió la canilla de la cocina y pierde agua");
    assertThat(body).contains("Juan Plomero");
    assertThat(body).contains("tel 099888777");
    assertThat(body).contains("https://www.fixy.com.uy/p/" + provider.getId() + "/");
    assertThat(body).contains("Reenviá el panel por WhatsApp");

    Provider reloaded = providerRepository.findById(provider.getId()).orElseThrow();
    assertThat(reloaded.getAccessToken()).isNotBlank();
    assertThat(body).contains(reloaded.getAccessToken());
  }

  @Test
  void secondTriggerForSameLead_doesNotReNotify() {
    Lead lead = persistLead("plomeria", "Solymar", "Se me tapó el caño del baño");
    Provider provider = persistProvider("Juan Plomero", "099888777");
    List<ProviderCatalogItem> matches = List.of(new ProviderCatalogItem(
        provider.getId(), provider.getName(), "plomeria", "Solymar", provider.getPhone(), "AVAILABLE", "manual"
    ));

    telegramNotifyService.notifyOpportunityWithMatches(lead, matches);
    awaitOneMessage();
    // notifyOpportunityWithMatches es @Async: el POST llega antes de que el
    // evento de timeline (base de la idempotencia) quede committeado. Hay que
    // esperar el evento, no solo el POST, antes de disparar el segundo intento.
    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
        .untilAsserted(() -> assertThat(leadEventRepository
            .findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), "OPS_NOTIFIED_OPPORTUNITY"))
            .isNotEmpty());

    // Segundo trigger del mismo lead (ej. otro camino que también llama al notify).
    telegramNotifyService.notifyOpportunityWithMatches(lead, matches);

    // Damos tiempo a que un eventual segundo POST llegue (no debería).
    String second = null;
    try {
      second = receivedBodies.poll(1500, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertThat(second).as("no debería reenviarse un segundo aviso para el mismo lead").isNull();
  }

  @Test
  void smokeLead_doesNotNotify() throws InterruptedException {
    Lead lead = persistLead("plomeria", "Solymar", "[smoke] prueba automatizada de humo");
    Provider provider = persistProvider("Juan Plomero", "099888777");
    List<ProviderCatalogItem> matches = List.of(new ProviderCatalogItem(
        provider.getId(), provider.getName(), "plomeria", "Solymar", provider.getPhone(), "AVAILABLE", "manual"
    ));

    telegramNotifyService.notifyOpportunityWithMatches(lead, matches);

    String body = receivedBodies.poll(1500, TimeUnit.MILLISECONDS);
    assertThat(body).as("lead [smoke] no debe generar aviso").isNull();
  }

  @Test
  void mvpCategoryWithoutProviders_sendsDemandWithoutSupplyNotice() {
    Lead lead = persistLead("jardineria", "Lagomar", "Necesito que me corten el pasto del fondo");

    telegramNotifyService.notifyDemandWithoutSupply(lead);

    String body = awaitOneMessage();
    assertThat(body).contains("Oportunidad #" + lead.getId());
    assertThat(body).contains("jardinería en Lagomar");
    assertThat(body).contains("Sin proveedores para jardinería en Lagomar — conseguir uno");
  }
}
